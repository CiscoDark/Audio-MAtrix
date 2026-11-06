# AudioMatrix — Step 2: Zero-Lag Per-App Device Routing & App Gain (`IAudioService` & `IPlayer` Binder)

With **Step 1 (Shizuku ADB Link & Audio Focus Lock)** verified, **Step 2** locks in **0ms Native Per-App Output Routing** and **Per-App Volume/Gain Control** using direct Shizuku-wrapped `IAudioService` and `IPlayer` Binder calls—eliminating software audio re-streaming lag while keeping the main Mixer UI clean and logging all hardware/Binder results in **Settings → Shizuku ADB Command Log**.

## User Review & Critical Decisions

> [!IMPORTANT]
> **Confirmed Step 2 Choices**:
> - **Feature Scope**: Step 2 focuses strictly on **Per-App Output Device Routing** (Target Output Sink dropdown on each app card) and **Per-App Gain Control** (`APP GAIN` slider on each app card).
> - **Zero-Lag Execution Mechanism**: Use direct **Shizuku `IPlayer` and `IAudioService` Binder calls** (`ShizukuBinderWrapper`) on `Dispatchers.IO` with **0ms audio latency**, plus automatic re-application when an app switches tracks (`onPlaybackConfigChanged`).
> - **Minimal UI Posture**: Keep the main Mixer UI free of popup banners; record every `IAudioService` and `IPlayer` Binder transaction and return code in **Settings → Shizuku ADB Command Log**.

---

## 1. Overview & Core Concept

- **What It Does**:
  1. **Zero-Lag Per-App Output Routing**: Selecting a target sink (e.g., *Phone Stereo Speaker*, *Bluetooth A2DP Earbuds*, *Wired/USB DAC*, or *Reset to System Default*) on any app card routes that app's `UID` directly at the Android Audio Policy HAL level via Shizuku-wrapped `IAudioService` Binder calls without capturing or delaying PCM audio.
  2. **Direct `IPlayer` Per-App Volume Scaling**: Dragging the **APP GAIN** slider (`0%–100%`) locates all active `AudioPlaybackConfiguration` players (`piid`) belonging to that app's `UID`, wraps their underlying `android.media.IPlayer` Binder in `ShizukuBinderWrapper`, and invokes `IPlayer.setVolume(float)` directly in the app's native `AudioTrack` / `MediaPlayer` mixer. At `0%`, it also enforces `appops set --uid <pkg> PLAY_AUDIO ignore` (and restores `allow` above `0%`) so muted apps are guaranteed silent even if they spawn a new player.
  3. **Persistent Gain & Route Across Song Skips**: Media apps (Spotify, YouTube Music, TikTok) create a new `IPlayer` instance whenever a track changes. `AudioMatrixViewModel` listens to `AudioPlaybackCallback.onPlaybackConfigChanged` and immediately re-applies each app's configured volume gain and device route to newly spawned players.

---

## 2. User Experience & Visual Design

- **Key User Flows**:
  1. **Route an App to a Specific Connected Sink**:
     - In the **Per-App Audio Router** section, tap the **Target Output Sink** dropdown on any app card (either an actively playing app or one added via **+ Pick App**).
     - Choose any currently connected hardware sink (e.g., *Phone Stereo Speaker* or *Bluetooth A2DP Buds*).
     - `ShizukuAudioController.setNativeUidDeviceAffinity` executes immediately on `Dispatchers.IO` and logs the exact `IAudioService` Binder method and status code in **Settings → Shizuku ADB Command Log**.
  2. **Adjust Individual App Volume (`APP GAIN`)**:
     - Drag the **APP GAIN** slider (`0%` to `100%`) on an app card with zero UI jitter.
     - Debounced (`25ms`) background dispatch invokes Shizuku-wrapped `IPlayer.setVolume(gain)` on every active player (`piid`) for that app's `UID` and logs the affected player IDs (`piid`) in the Settings log.
- **Visual Identity & Theme**:
  - Retains the clean Material 3 dynamic system theme (`Space Grotesk` headings, `JetBrains Mono` UID badges and Settings logs) with responsive direct slider binding.

---

## 3. Key Product Decisions & Trade-Offs

- **Decision 1: Shizuku-Wrapped `IPlayer` Binder (`mIPlayerShell` -> `IPlayer$Stub`)**
  - *Chosen Approach*: Query `getActivePlaybackConfigurations()` from the Shizuku-wrapped `IAudioService` (running as `shell` UID `2000` which holds `MODIFY_AUDIO_ROUTING`), extract each matching UID's `IPlayer` `IBinder` from `PlayerProxy` / `mIPlayerShell`, wrap it in `ShizukuBinderWrapper`, and invoke `setVolume(Float)`.
  - *Why*: Calling `audioManager.activePlaybackConfigurations` from a normal app UID strips `clientUid` (`-1`) and `playerProxy` (`null`) on Android 12–14. Fetching configurations through `ShizukuBinderWrapper(IAudioService)` preserves full `clientUid`, `playerInterfaceId (piid)`, and `IPlayer` Binder references.
- **Decision 2: Multi-Tier `IAudioService` UID Routing with Strategy Fallback**
  - *Chosen Approach*:
    1. First invoke `IAudioService.setUidDeviceAffinity(uid, int[] types, String[] addresses)` via `ShizukuBinderWrapper` using internal `AudioSystem` device bitmasks (`0x2` Speaker, `0x80` Bluetooth A2DP, etc.).
    2. If the OEM AudioPolicy rejects per-UID affinity (e.g., when an app shares a media session or on specific OEM ROMs), fall back to `IAudioService.setPreferredDeviceForStrategy(STRATEGY_MEDIA, AudioDeviceAttributes)` via `ShizukuBinderWrapper` so the hardware sink switches immediately at 0ms latency.
  - *Why*: Guarantees hardware output switching works across AOSP, Pixel, Samsung, and Xiaomi Android 13/14 builds without spinning up software audio capture.

---

## 4. Technical Architecture & Data Strategy *(Technical Reference)*

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                   PerAppRouterCard (MixerDashboardScreen)                   │
│  • Target Output Sink Dropdown  ──► viewModel.routeAppToDevice(uid, devId)  │
│  • APP GAIN Slider (0%..100%)   ──► viewModel.setAppVolume(uid, volume)     │
└───────────────────────────────────────┬─────────────────────────────────────┘
                                        │ Dispatchers.IO (25ms debounce on slider)
                                        ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                ShizukuAudioController (ShizukuBinderWrapper)                │
│                                                                             │
│  1. Per-App Output Routing (`setNativeUidDeviceAffinity`):                  │
│     ├─ Tier 1: IAudioService.setUidDeviceAffinity(uid, [internalType], [mac])│
│     ├─ Tier 2: IAudioService.setPreferredDeviceForStrategy(0, AudioDevAttr) │
│     └─ Logs exact Binder method & return code to Settings Command Log       │
│                                                                             │
│  2. Per-App Volume (`setNativeAppVolume`):                                  │
│     ├─ Privileged IAudioService.getActivePlaybackConfigurations()           │
│     ├─ Match config.clientUid == targetUid                                  │
│     ├─ Extract IPlayer IBinder -> ShizukuBinderWrapper -> setVolume(gain)   │
│     ├─ Enforce PLAY_AUDIO appops mute gate when gain == 0f                  │
│     └─ Re-applied automatically on AudioPlaybackCallback track changes      │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Interactive Component & State Mapping
- **`routeAppToDevice(uid, deviceId)`**: Updates `ActiveAppStream.assignedDeviceId` in `_activeStreams`, resolves the `OutputDevice` from `_connectedOutputs`, and dispatches `ShizukuAudioController.setNativeUidDeviceAffinity(uid, packageName, targetDevice, audioManager)` on `Dispatchers.IO`.
- **`setAppVolume(uid, volume)`**: Updates `ActiveAppStream.volume` immediately for smooth slider tracking, debounces by `25ms`, and dispatches `ShizukuAudioController.setNativeAppVolume(audioManager, uid, packageName, volume)` on `Dispatchers.IO`.
- **`onPlaybackConfigChanged` Auto-Sync**: Whenever an app spawns a new `AudioTrack` / `MediaPlayer`, re-applies any non-default `volume` (`!= 1.0f`) and `assignedDeviceId` via `ShizukuAudioController` so settings never reset between songs.
