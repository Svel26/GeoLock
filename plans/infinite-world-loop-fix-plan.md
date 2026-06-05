# GeoLock Infinite World Loop - Refined Fix Plan

## Core Priority: Smooth Portal-Based Infinite World Looping

Based on your feedback:
- ❌ **No teleport fallback needed** — portals MUST handle all boundary crossing
- 🟢 **Performance is not a priority** — visual quality comes first
- 🟢 **Portals first** — make the portal stitching and chunk loading perfect

---

## The 5 Critical Issues (In Priority Order)

### 🔴 Critical #1: `OffsetContext` Uses `Math.round()` → Off-by-One at Boundaries

**File:** [`ToroidalNoise.java:180-182`](src/main/java/bittree/geolock/worldgen/ToroidalNoise.java:180)

```java
public int blockX() { return (int) Math.round(original.blockX() + offsetX); }
```

`Math.round()` uses banker's rounding (round half up). When `offsetX` has a fractional component (e.g., `0.3` from the +0.5 center offset), this can snap to the wrong integer at boundary crossings. **Fix:** Use `Math.floor()` consistently.

### 🔴 Critical #2: NoiseChunk Trilinear Cache Contamination

When `ToroidalNoise.blend()` evaluates 2-4 offset samples at the boundary, the `NoiseChunk` internal trilinear cache may return **stale unwrapped values** instead of freshly evaluating at the shifted coordinate. The `fillArray()` cancellation in mixins partially helps, but doesn't fully bypass the cell-corner cache.

**Fix:** Ensure all wrapped evaluations use a context that the `NoiseChunk` cannot cache — by using a unique `FunctionContext` that the cache doesn't recognize.

### 🔴 Critical #3: 128-Block Blend Zone Too Small

Continental noise features span 300-500+ blocks. A 128-block blend creates a visible transition line. **Fix:** Increase to 256 blocks minimum, and make it configurable separately for different noise scales if needed.

### 🟡 Issue #4: Portal Stitcher — Single Zone vs. Four Edges

**File:** [`PortalStitcher.java:134-149`](src/main/java/bittree/geolock/worldgen/PortalStitcher.java:134)

Creates one `WrappingZone` covering both X and Z axes. Need to verify this wraps corners correctly (e.g., NE corner → SW corner). Also ensure zone thickness captures the entire blend zone area.

**Fix:** If one zone doesn't cover corners properly, create zones for each edge + corners.

### 🟡 Issue #5: Ad-Hoc Wrapping Inconsistencies

Every file uses different wrapping logic:
- `ToroidalNoise.blend()` → modulo + center offset
- `LevelMixin` → `Math.floor(mod) - halfW`
- `BlockMixin` → hardcoded `±halfW` comparisons
- `PortalChunkLoaderHelper` → threshold-based
- `WorldgenRandomMixin` → `Math.floorMod()` with chunk coords

**Fix:** Create a single `CoordWrappingUtil` class used by all systems.

---

## Execution Plan (Simplified to 5 Phases)

### Phase 1: Fix the Noise System (ToroidalNoise + Mixins)
- Fix `OffsetContext.blockX()/blockZ()` to use `Math.floor()` instead of `Math.round()`
- Fix large-offset handling in OffsetContext
- Increase `blendZoneWidth` default from 128 → 256
- Ensure all 8 density mixins bypass `NoiseChunk` cache consistently

### Phase 2: Create CoordWrappingUtil
- Single utility class with `wrapBlockCoord()`, `wrapChunkCoord()`, `wrapBlockPos()`
- Replace all ad-hoc wrapping in LevelMixin, BlockMixin, RenderChunkRegionMixin
- Replace wrapping in PortalChunkLoaderHelper and PortalStitcher

### Phase 3: Fix Portal System
- Verify PortalStitcher creates correct wrapping zones (four edges + corners)
- Increase chunk loading threshold and refresh rate
- Pre-load boundary chunks aggressively for smooth portal transitions

### Phase 4: Clean Up
- Remove teleport fallback from `onPlayerTick` (or keep as disabled safety net)
- Remove `wrapOverworldNoiseRouter()` dead code
- Remove `CarverCheck.java` test mixin

### Phase 5: Polish
- Update config defaults (blendZoneWidth=256)
- Add logging for debugging portal zone creation
- Test at different world sizes
