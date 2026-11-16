#pragma once

#include <atomic>
#include <cstdint>

// Shared fast forward state.
//
// The user asks for a multiplier (2x / 3x / 4x). Everything that has to follow the emulated speed
// reads it from here: the vsync pacing (LatteTime_CalculateTimeBetweenVSync), the guest clock
// (PPCTimer_getFromRDTSC), the audio producer (AXOut_update) and the audio output resampling
// (IAudioAPI::FeedBlock). Making it a header-only singleton keeps CemuAudio independent from
// CemuCafe, which would otherwise be needed to reach the emulation thread.
//
// Requested state and applied state are separate on purpose: while a game is loading (it does not
// present any frame) scaling guest time only disturbs its event-flag handshakes and cannot make
// anything faster, so the vsync timer suspends fast forward and the effective multiplier drops
// back to 1 while the user's request is remembered.
namespace FastForward
{
	inline std::atomic_bool s_enabled{ false };
	// Requested multiplier. Only used while enabled is true.
	inline std::atomic_int s_speed{ 3 };
	// Multiplier actually applied right now. 1 while fast forward is off or suspended.
	inline std::atomic_int s_effectiveSpeed{ 1 };
	// HighResolutionTimer tick of the last time the game requested a screen buffer swap.
	inline std::atomic<uint64_t> s_lastFlipTick{ 0 };

	inline bool IsEnabled()
	{
		return s_enabled.load(std::memory_order_relaxed);
	}

	inline int GetSpeed()
	{
		return s_speed.load(std::memory_order_relaxed);
	}

	inline int GetEffectiveSpeed()
	{
		return s_effectiveSpeed.load(std::memory_order_relaxed);
	}

	// Called from the JNI thread when the user toggles fast forward or picks another multiplier.
	inline void SetState(bool enabled, int speed)
	{
		if (speed < 1)
			speed = 1;
		s_speed.store(speed, std::memory_order_relaxed);
		s_enabled.store(enabled, std::memory_order_relaxed);
	}

	// Called by the vsync timer, which is the single owner of the effective multiplier.
	inline void UpdateEffectiveSpeed(bool stalled)
	{
		const bool active = s_enabled.load(std::memory_order_relaxed) && !stalled;
		s_effectiveSpeed.store(active ? s_speed.load(std::memory_order_relaxed) : 1, std::memory_order_relaxed);
	}

	// Stop applying fast forward without forgetting the requested state.
	inline void Suspend()
	{
		s_effectiveSpeed.store(1, std::memory_order_relaxed);
	}

	// Called by the game thread whenever it asks for a new scan buffer.
	inline void NotifyFlipRequested(uint64_t tick)
	{
		s_lastFlipTick.store(tick, std::memory_order_relaxed);
	}

	inline void ResetFlipTimestamp()
	{
		s_lastFlipTick.store(0, std::memory_order_relaxed);
	}

	// A game that has not swapped a frame for a while is loading or otherwise blocked, which fast
	// forward cannot improve.
	inline bool IsStalled(uint64_t nowTick, uint64_t stallTicks)
	{
		const uint64_t lastFlip = s_lastFlipTick.load(std::memory_order_relaxed);
		return lastFlip == 0 || nowTick < lastFlip || (nowTick - lastFlip) > stallTicks;
	}
}
