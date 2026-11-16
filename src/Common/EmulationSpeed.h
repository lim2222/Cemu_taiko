#pragma once

#include <atomic>
#include <cstdint>

// Shared emulation speed state: fast forward and slow motion.
//
// Both features scale the emulated console's time. Everything that has to follow the emulated
// speed reads the effective fraction from here:
//
//   - the vsync pacing (LatteTime_CalculateTimeBetweenVSync)
//   - the guest clock (PPCTimer_getFromRDTSC)
//   - the audio producer (AXOut_update)
//   - the audio output resampling (IAudioAPI::ResampleForEmulationSpeed)
//
// Fast forward is represented as num/den with num > den (2x..4x), slow motion with den > num
// (1/2x..1/4x). Making it a header-only singleton keeps CemuAudio independent from CemuCafe,
// which would otherwise be needed to reach the emulation thread.
//
// Requested state and applied state are separate on purpose: while a game is loading (it does not
// present any frame) scaling guest time up only disturbs its event-flag handshakes and cannot make
// anything faster, so the vsync timer suspends fast forward and the effective fraction drops back
// to 1/1 while the user's request is remembered. Slow motion has no such guard, running slowly
// while loading is harmless.
namespace EmulationSpeed
{
	// Requested fast forward multiplier (2..4). Only used while enabled.
	inline std::atomic_bool s_fastForwardEnabled{ false };
	inline std::atomic_int s_fastForwardSpeed{ 3 };

	// Requested slow motion divisor (2..4). Only used while enabled.
	inline std::atomic_bool s_slowMotionEnabled{ false };
	inline std::atomic_int s_slowMotionDivisor{ 2 };

	// Emulated time scale actually applied right now. 1/1 while both features are off or while
	// fast forward is suspended.
	inline std::atomic_int s_effectiveNum{ 1 };
	inline std::atomic_int s_effectiveDen{ 1 };

	// HighResolutionTimer tick of the last time the game requested a screen buffer swap.
	inline std::atomic<uint64_t> s_lastFlipTick{ 0 };

	inline bool IsFastForwardEnabled()
	{
		return s_fastForwardEnabled.load(std::memory_order_relaxed);
	}

	inline int GetFastForwardSpeed()
	{
		return s_fastForwardSpeed.load(std::memory_order_relaxed);
	}

	inline bool IsSlowMotionEnabled()
	{
		return s_slowMotionEnabled.load(std::memory_order_relaxed);
	}

	inline int GetSlowMotionDivisor()
	{
		return s_slowMotionDivisor.load(std::memory_order_relaxed);
	}

	// Guest time advances num/den times as fast as real time.
	inline int GetEffectiveNum()
	{
		return s_effectiveNum.load(std::memory_order_relaxed);
	}

	inline int GetEffectiveDen()
	{
		return s_effectiveDen.load(std::memory_order_relaxed);
	}

	// Called from the JNI thread whenever the user toggles fast forward / slow motion or picks
	// another multiplier. All four values are passed at once so the app never leaves the two
	// features in a mixed state.
	inline void SetState(bool fastForwardEnabled, int fastForwardSpeed, bool slowMotionEnabled, int slowMotionDivisor)
	{
		if (fastForwardSpeed < 2)
			fastForwardSpeed = 2;
		else if (fastForwardSpeed > 4)
			fastForwardSpeed = 4;
		if (slowMotionDivisor < 2)
			slowMotionDivisor = 2;
		else if (slowMotionDivisor > 4)
			slowMotionDivisor = 4;

		s_fastForwardSpeed.store(fastForwardSpeed, std::memory_order_relaxed);
		s_slowMotionEnabled.store(slowMotionEnabled, std::memory_order_relaxed);
		s_slowMotionDivisor.store(slowMotionDivisor, std::memory_order_relaxed);
		s_fastForwardEnabled.store(fastForwardEnabled, std::memory_order_relaxed);
	}

	// Called by the vsync timer, which is the single owner of the effective fraction. Fast forward
	// wins if both are requested, even though the app keeps the two toggles exclusive anyway.
	inline void UpdateEffective(bool stalled)
	{
		int num = 1;
		int den = 1;
		if (s_fastForwardEnabled.load(std::memory_order_relaxed))
		{
			if (!stalled)
				num = s_fastForwardSpeed.load(std::memory_order_relaxed);
		}
		else if (s_slowMotionEnabled.load(std::memory_order_relaxed))
		{
			den = s_slowMotionDivisor.load(std::memory_order_relaxed);
		}
		s_effectiveNum.store(num, std::memory_order_relaxed);
		s_effectiveDen.store(den, std::memory_order_relaxed);
	}

	// Stop applying any speed change without forgetting the requested values.
	inline void Suspend()
	{
		s_effectiveNum.store(1, std::memory_order_relaxed);
		s_effectiveDen.store(1, std::memory_order_relaxed);
	}

	// Called when a new title starts. Requested values are kept, speed starts normal until the
	// first frame arrives (see UpdateEffective).
	inline void Reset()
	{
		s_effectiveNum.store(1, std::memory_order_relaxed);
		s_effectiveDen.store(1, std::memory_order_relaxed);
		s_lastFlipTick.store(0, std::memory_order_relaxed);
	}

	// Called by the game thread whenever it asks for a new scan buffer.
	inline void NotifyFlipRequested(uint64_t tick)
	{
		s_lastFlipTick.store(tick, std::memory_order_relaxed);
	}

	// A game that has not swapped a frame for a while is loading or otherwise blocked, which fast
	// forward cannot improve.
	inline bool IsStalled(uint64_t nowTick, uint64_t stallTicks)
	{
		const uint64_t lastFlip = s_lastFlipTick.load(std::memory_order_relaxed);
		return lastFlip == 0 || nowTick < lastFlip || (nowTick - lastFlip) > stallTicks;
	}
}
