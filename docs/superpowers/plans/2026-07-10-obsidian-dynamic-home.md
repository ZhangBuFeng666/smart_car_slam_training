# Obsidian Dynamic Home Implementation Plan

> The 2.5D vehicle steps in this plan are superseded by `2026-07-10-x3-360-stage.md`.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the approved black-gold Android home stage with a real iCar X3 2.5D animated vehicle and preserve all existing control behavior.

**Architecture:** Keep motion math in a pure Kotlin `VehicleMotionSpec` object so it can be unit tested. Put bitmap rendering, radar animation, touch parallax, and lifecycle cleanup in a dedicated `AnimatedVehicleStageView`; integrate the view into the existing programmatic home layout without changing `CarApi` contracts.

**Tech Stack:** Kotlin 1.9.24, Android Canvas/Camera/Matrix/ValueAnimator, Java JUnit 4, Android Gradle Plugin 8.5.0.

---

### Task 1: Define and test motion behavior

**Files:**
- Create: `app/src/main/java/com/example/icarcontroller/VehicleMotionSpec.kt`
- Create: `app/src/test/java/com/example/icarcontroller/VehicleMotionSpecTest.java`

- [ ] **Step 1: Write failing tests for drag clamping, perspective limits, idle offsets, and scan angle**

```java
assertEquals(1.0f, VehicleMotionSpec.dragFraction(300f, 200f), 0.001f);
assertEquals(-7.0f, VehicleMotionSpec.rotationYDegrees(-1f), 0.001f);
assertEquals(-4.0f, VehicleMotionSpec.idleTranslationDp(0.25f), 0.001f);
assertEquals(180.0f, VehicleMotionSpec.scanAngleDegrees(0.5f), 0.001f);
```

- [ ] **Step 2: Run the focused test and confirm it fails because `VehicleMotionSpec` is missing**

Run: `gradle.bat testDebugUnitTest --tests com.example.icarcontroller.VehicleMotionSpecTest --no-daemon --console=plain`

Expected: compilation failure naming the missing `VehicleMotionSpec`.

- [ ] **Step 3: Implement the minimal pure Kotlin motion API**

```kotlin
object VehicleMotionSpec {
    @JvmStatic fun dragFraction(dragPx: Float, widthPx: Float): Float
    @JvmStatic fun rotationYDegrees(fraction: Float): Float
    @JvmStatic fun idleTranslationDp(progress: Float): Float
    @JvmStatic fun idleRotationDegrees(progress: Float): Float
    @JvmStatic fun scanAngleDegrees(progress: Float): Float
    @JvmStatic fun idleDurationMillis(): Int
    @JvmStatic fun settleDurationMillis(): Int
}
```

- [ ] **Step 4: Re-run the focused test and confirm all motion tests pass**

### Task 2: Create the real X3 transparent asset

**Files:**
- Create: `app/src/main/res/drawable-nodpi/icar_x3_front.png`

- [ ] **Step 1: Extract page 5 image and soft mask from the supplied manual with `pdfimages`**
- [ ] **Step 2: Combine RGB and mask into an ARGB PNG and crop the left three-quarter X3 view**
- [ ] **Step 3: Inspect the PNG for transparent corners, intact antennas, wheels, and no black rectangle**

### Task 3: Implement the animated vehicle view

**Files:**
- Create: `app/src/main/java/com/example/icarcontroller/AnimatedVehicleStageView.kt`

- [ ] **Step 1: Load `icar_x3_front`, prepare radar/shadow/status paints, and scale the bitmap with stable aspect ratio**
- [ ] **Step 2: Add a lifecycle-aware repeating animator using `VehicleMotionSpec.idleDurationMillis()`**
- [ ] **Step 3: Render idle translation, subtle rotation, radar sweep, and connection-state colors**
- [ ] **Step 4: Add horizontal drag capture after touch slop and animate back using `VehicleMotionSpec.settleDurationMillis()`**
- [ ] **Step 5: Stop all animators in `onDetachedFromWindow()` and recycle no resource-owned bitmap manually**

### Task 4: Integrate the black-gold home stage

**Files:**
- Modify: `app/src/main/java/com/example/icarcontroller/MainActivity.kt`
- Modify: `app/src/main/java/com/example/icarcontroller/InteractionSpec.kt`
- Modify: `app/src/test/java/com/example/icarcontroller/InteractionSpecTest.java`
- Create: `app/src/main/res/drawable/obsidian_stage_bg.xml`
- Create: `app/src/main/res/drawable/obsidian_action_bg.xml`
- Create: `app/src/main/res/drawable/obsidian_primary_bg.xml`

- [ ] **Step 1: Add a failing interaction-spec test requiring a taller immersive stage and motion timings**
- [ ] **Step 2: Run the focused interaction test and confirm the new assertions fail**
- [ ] **Step 3: Add the required dimensions and timings to `InteractionSpec`**
- [ ] **Step 4: Replace `VehicleSilhouetteView` with `AnimatedVehicleStageView` in the home stage**
- [ ] **Step 5: Remove floating card actions, add the compact metrics row and one black-gold patrol action**
- [ ] **Step 6: Reflect successful/failed HTTP requests in the home view connection indicator without changing request URLs**
- [ ] **Step 7: Remove the obsolete private `VehicleSilhouetteView` implementation and its unused imports**

### Task 5: Verify the deliverable

**Files:**
- Verify only; no commit or push.

- [ ] **Step 1: Run `testDebugUnitTest` and confirm zero failures**
- [ ] **Step 2: Run `assembleDebug` and confirm the debug APK is produced**
- [ ] **Step 3: Inspect `git diff --check` and the scoped diff for whitespace or unrelated changes**
- [ ] **Step 4: Locate ADB; if available, install and launch on the connected phone, otherwise report that device visual verification remains pending**
- [ ] **Step 5: Keep all work uncommitted until the user approves the device result**
