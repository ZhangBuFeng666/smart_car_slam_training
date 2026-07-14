# iCar Web Desktop Client Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a React desktop Web client that mirrors the Android app's Patrol, Drive, AI, Vision, and Navigation pages and safely controls the car over the existing Jetson and Jarvis APIs.

**Architecture:** Create an isolated Vite application under `web/`. Page components consume typed Jetson and Jarvis clients through focused hooks; global preferences store theme and service addresses. Drive input is implemented as an explicit safety state machine so release, blur, visibility changes, and emergency stop all stop the vehicle.

**Tech Stack:** React 19, TypeScript, Vite, React Router, Vitest, Testing Library, Playwright, native Fetch API, CSS custom properties.

---

## File Structure

```text
web/
├── package.json
├── vite.config.ts
├── playwright.config.ts
├── index.html
├── src/
│   ├── main.tsx                   # React entry point
│   ├── App.tsx                    # Routes and application shell
│   ├── styles/global.css          # Theme tokens and responsive shell
│   ├── config/connection.ts       # Persisted Jetson/Jarvis addresses
│   ├── api/http.ts                # Timeout and JSON request primitive
│   ├── api/jetson.ts              # Vehicle, task, speech and navigation API
│   ├── api/jarvis.ts              # Conversation and control-task API
│   ├── hooks/useVehicleHealth.ts  # Health polling
│   ├── hooks/useDriveControls.ts  # Safe pointer/keyboard motion state
│   ├── components/AppShell.tsx    # Top bar, sidebar and content outlet
│   ├── components/StatusRail.tsx  # Connection and vehicle status
│   ├── components/CameraView.tsx  # MJPEG loading/error presentation
│   ├── pages/PatrolPage.tsx
│   ├── pages/DrivePage.tsx
│   ├── pages/AiPage.tsx
│   ├── pages/VisionPage.tsx
│   └── pages/NavigationPage.tsx
└── tests/
    ├── setup.ts
    ├── connection.test.ts
    ├── drive-controls.test.tsx
    ├── app-navigation.test.tsx
    └── smoke.spec.ts
```

### Task 1: Scaffold the Web Workspace

**Files:**
- Create: `web/package.json`
- Create: `web/vite.config.ts`
- Create: `web/playwright.config.ts`
- Create: `web/index.html`
- Create: `web/src/main.tsx`
- Create: `web/src/App.tsx`
- Create: `web/src/tests/setup.ts`
- Modify: `.gitignore`

- [ ] **Step 1: Add the package manifest and test scripts**

```json
{
  "name": "icar-web",
  "private": true,
  "version": "0.1.0",
  "type": "module",
  "scripts": {
    "dev": "vite --host 0.0.0.0",
    "build": "tsc -b && vite build",
    "test": "vitest run",
    "test:e2e": "playwright test"
  },
  "dependencies": {
    "@vitejs/plugin-react": "latest",
    "lucide-react": "latest",
    "react": "latest",
    "react-dom": "latest",
    "react-router-dom": "latest"
  },
  "devDependencies": {
    "@playwright/test": "latest",
    "@testing-library/jest-dom": "latest",
    "@testing-library/react": "latest",
    "@testing-library/user-event": "latest",
    "@types/react": "latest",
    "@types/react-dom": "latest",
    "jsdom": "latest",
    "typescript": "latest",
    "vite": "latest",
    "vitest": "latest"
  }
}
```

- [ ] **Step 2: Add Vite, TypeScript, Vitest and Playwright configuration**

Configure `vite.config.ts` with the React plugin, `test.environment = "jsdom"`, and `setupFiles = "./src/tests/setup.ts"`. Configure Playwright to start `npm run dev -- --port 4173` and use `http://127.0.0.1:4173`.

- [ ] **Step 3: Add a minimal app entry and ignore generated files**

Render `<App />` from `main.tsx`. Add `web/node_modules/`, `web/dist/`, and `web/test-results/` to `.gitignore`.

- [ ] **Step 4: Install dependencies and verify the scaffold**

Run: `cd web; npm install; npm run build`

Expected: Vite production build exits with code 0.

- [ ] **Step 5: Commit**

```bash
git add .gitignore web
git commit -m "feat(web): scaffold desktop client"
```

### Task 2: Connection Configuration and Typed API Clients

**Files:**
- Create: `web/src/config/connection.ts`
- Create: `web/src/api/http.ts`
- Create: `web/src/api/jetson.ts`
- Create: `web/src/api/jarvis.ts`
- Create: `web/src/tests/connection.test.ts`

- [ ] **Step 1: Write failing connection tests**

```ts
expect(normalizeBaseUrl("10.161.57.230:8000")).toBe("http://10.161.57.230:8000")
expect(cameraStreamUrl(config)).toBe("http://10.161.57.230:8000/camera/stream")
expect(moveUrl(config, "forward", 0.2)).toContain("direction=forward")
```

- [ ] **Step 2: Run the tests and confirm failure**

Run: `cd web; npm test -- connection.test.ts`

Expected: FAIL because the configuration and client modules do not exist.

- [ ] **Step 3: Implement persisted connection configuration**

Define `ConnectionConfig { jetsonBaseUrl: string; jarvisBaseUrl: string }`, defaulting to `http://10.161.57.230:8000` and `http://10.161.57.230:8100`. Normalize schemes and remove trailing slashes before saving to local storage.

- [ ] **Step 4: Implement HTTP and API clients**

The HTTP primitive must support `AbortSignal.timeout`, JSON parsing, and an `HttpError` containing status and response text. `jetson.ts` exposes health, task, movement, stop, speech, navigation, and camera URL functions. `jarvis.ts` exposes conversations, messages, control tasks, preparation, start, and stop functions matching `contracts/jarvis-api-v1.json`.

- [ ] **Step 5: Run tests**

Run: `cd web; npm test -- connection.test.ts`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add web/src/config web/src/api web/src/tests/connection.test.ts
git commit -m "feat(web): add vehicle API clients"
```

### Task 3: Desktop Shell, Five Routes, and Themes

**Files:**
- Create: `web/src/components/AppShell.tsx`
- Create: `web/src/components/StatusRail.tsx`
- Create: `web/src/styles/global.css`
- Create: `web/src/pages/PatrolPage.tsx`
- Create: `web/src/pages/DrivePage.tsx`
- Create: `web/src/pages/AiPage.tsx`
- Create: `web/src/pages/VisionPage.tsx`
- Create: `web/src/pages/NavigationPage.tsx`
- Modify: `web/src/App.tsx`
- Create: `web/src/tests/app-navigation.test.tsx`

- [ ] **Step 1: Write a failing navigation test**

```tsx
render(<App />)
expect(screen.getByRole("link", { name: "巡逻" })).toBeInTheDocument()
expect(screen.getByRole("link", { name: "驾驶" })).toBeInTheDocument()
expect(screen.getByRole("link", { name: "AI" })).toBeInTheDocument()
expect(screen.getByRole("link", { name: "视觉" })).toBeInTheDocument()
expect(screen.getByRole("link", { name: "导航" })).toBeInTheDocument()
```

- [ ] **Step 2: Run the test and confirm failure**

Run: `cd web; npm test -- app-navigation.test.tsx`

Expected: FAIL because the shell and routes are absent.

- [ ] **Step 3: Implement the app shell**

Use one left sidebar with five routes: `/patrol`, `/drive`, `/ai`, `/vision`, `/navigation`. Add one global theme icon in the top bar and a connection settings popover. Use Lucide icons with accessible labels.

- [ ] **Step 4: Implement theme tokens and responsive layout**

Define light tokens using white, pale blue, cool gray, green, and red accents. Define dark tokens using near-black surfaces and restrained gold accents. Keep cards at 8px radius or less. At 1366x768, the sidebar, main content, and optional status rail must fit without horizontal scrolling.

- [ ] **Step 5: Add focused page shells**

Each page initially renders its correct title and semantic regions. Do not share one generic card grid between pages.

- [ ] **Step 6: Run navigation tests and build**

Run: `cd web; npm test -- app-navigation.test.tsx; npm run build`

Expected: tests and build pass.

- [ ] **Step 7: Commit**

```bash
git add web/src
git commit -m "feat(web): add five-page desktop shell"
```

### Task 4: Vehicle Health and Patrol Page

**Files:**
- Create: `web/src/hooks/useVehicleHealth.ts`
- Modify: `web/src/components/StatusRail.tsx`
- Modify: `web/src/pages/PatrolPage.tsx`
- Create: `web/src/tests/vehicle-health.test.tsx`

- [ ] **Step 1: Write failing polling-state tests**

Test transitions for loading, online, stale, and offline results with mocked timers and fetch responses.

- [ ] **Step 2: Run tests and confirm failure**

Run: `cd web; npm test -- vehicle-health.test.tsx`

Expected: FAIL because `useVehicleHealth` does not exist.

- [ ] **Step 3: Implement health polling**

Poll every three seconds while visible. Preserve the last successful payload, mark it stale after two failures, and stop polling on unmount.

- [ ] **Step 4: Build the patrol page**

Create a dominant parking-lot patrol surface, vehicle readiness strip, checkpoint list, anomaly timeline, and direct entries to Drive, AI, Vision, and Navigation. Reuse real health data and label unavailable data as unavailable.

- [ ] **Step 5: Run tests and build**

Run: `cd web; npm test -- vehicle-health.test.tsx; npm run build`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add web/src/hooks web/src/components/StatusRail.tsx web/src/pages/PatrolPage.tsx web/src/tests/vehicle-health.test.tsx
git commit -m "feat(web): add patrol workspace and health status"
```

### Task 5: Camera and Safe Desktop Driving

**Files:**
- Create: `web/src/components/CameraView.tsx`
- Create: `web/src/hooks/useDriveControls.ts`
- Modify: `web/src/pages/DrivePage.tsx`
- Create: `web/src/tests/drive-controls.test.tsx`

- [ ] **Step 1: Write failing safety tests**

```tsx
fireEvent.keyDown(window, { code: "KeyW" })
expect(move).toHaveBeenCalledWith("forward")
fireEvent.keyUp(window, { code: "KeyW" })
expect(stop).toHaveBeenCalledTimes(1)
fireEvent.blur(window)
expect(stop).toHaveBeenCalled()
fireEvent.keyDown(window, { code: "Space" })
expect(emergencyStop).toHaveBeenCalled()
```

Also assert that key events are ignored when the target is an input, textarea, select, or contenteditable element.

- [ ] **Step 2: Run tests and confirm failure**

Run: `cd web; npm test -- drive-controls.test.tsx`

Expected: FAIL because the hook does not exist.

- [ ] **Step 3: Implement the drive state machine**

Map W/A/S/D and arrow keys to four directions. Permit only one active direction. Send stop on key release, pointer release, pointer cancel, blur, hidden visibility state, connection error, and unmount. Space always calls emergency stop unless the event originates in an editable element.

- [ ] **Step 4: Implement camera states**

Render MJPEG with loading, connected, reconnecting, and unavailable overlays. Offer retry and full-screen actions. Camera errors must not disable emergency stop.

- [ ] **Step 5: Build the desktop drive page**

Keep video, controls, speed presets, chassis state, and emergency stop in one 1366x768 viewport. Use tactile icon buttons and stable control dimensions. Show keyboard key labels as secondary hints.

- [ ] **Step 6: Run tests and build**

Run: `cd web; npm test -- drive-controls.test.tsx; npm run build`

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add web/src/components/CameraView.tsx web/src/hooks/useDriveControls.ts web/src/pages/DrivePage.tsx web/src/tests/drive-controls.test.tsx
git commit -m "feat(web): add low-latency safe driving"
```

### Task 6: Jarvis AI Workspace

**Files:**
- Create: `web/src/components/ConversationList.tsx`
- Create: `web/src/components/ControlTaskCard.tsx`
- Create: `web/src/hooks/useJarvisConversation.ts`
- Modify: `web/src/pages/AiPage.tsx`
- Create: `web/src/tests/jarvis-state.test.ts`

- [ ] **Step 1: Write failing Jarvis state tests**

Cover conversation loading and control task states `DRAFT`, `PREPARING`, `READY`, `PREPARATION_FAILED`, `STARTING`, `RUNNING`, `COMPLETED`, `STOPPED`, and `FAILED`. Assert that only `READY` enables Start.

- [ ] **Step 2: Run tests and confirm failure**

Run: `cd web; npm test -- jarvis-state.test.ts`

Expected: FAIL because the state mapper is absent.

- [ ] **Step 3: Implement conversation state and polling**

Load conversations, select the last active conversation, send user messages, render assistant replies, and poll active control tasks without duplicating cards.

- [ ] **Step 4: Build the AI page**

Use a fixed conversation sidebar, central chat timeline, bottom composer, and inline control-task cards. Require explicit confirmation before Start and expose Retry only for preparation failure.

- [ ] **Step 5: Run tests and build**

Run: `cd web; npm test -- jarvis-state.test.ts; npm run build`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add web/src/components web/src/hooks/useJarvisConversation.ts web/src/pages/AiPage.tsx web/src/tests/jarvis-state.test.ts
git commit -m "feat(web): add Jarvis conversation workspace"
```

### Task 7: Vision and Navigation Workspaces

**Files:**
- Create: `web/src/components/DetectionOverlay.tsx`
- Create: `web/src/components/NavigationMap.tsx`
- Modify: `web/src/pages/VisionPage.tsx`
- Modify: `web/src/pages/NavigationPage.tsx`
- Create: `web/src/tests/vision-navigation.test.tsx`

- [ ] **Step 1: Write failing rendering tests**

Assert that Vision shows “训练中” when no model result exists, never invents detections, and renders supplied normalized boxes. Assert that Navigation validates finite x, y, and yaw values before sending a pose or goal.

- [ ] **Step 2: Run tests and confirm failure**

Run: `cd web; npm test -- vision-navigation.test.tsx`

Expected: FAIL because the components do not exist.

- [ ] **Step 3: Implement Vision**

Render the camera as the dominant surface. Overlay only detections received from the API. Show class, confidence, event counters, and model state in the side rail.

- [ ] **Step 4: Implement Navigation**

Render a map workspace with build-map workflow, initial pose, goal pose, DWA/TEB actions, and persistent stop navigation control. Until a live map endpoint exists, present the workflow and explicit “地图流未接入” state rather than a fake map.

- [ ] **Step 5: Run tests and build**

Run: `cd web; npm test -- vision-navigation.test.tsx; npm run build`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add web/src/components/DetectionOverlay.tsx web/src/components/NavigationMap.tsx web/src/pages/VisionPage.tsx web/src/pages/NavigationPage.tsx web/src/tests/vision-navigation.test.tsx
git commit -m "feat(web): add vision and navigation workspaces"
```

### Task 8: Browser QA and Local Runbook

**Files:**
- Create: `web/src/tests/smoke.spec.ts`
- Create: `web/README.md`
- Modify: `README.md`

- [ ] **Step 1: Add desktop smoke tests**

Visit all five routes at 1366x768 and 1920x1080. Assert the active route title is visible, the body has no horizontal overflow, the theme switch changes `data-theme`, and Drive contains camera, movement controls, speed, and emergency stop in the viewport.

- [ ] **Step 2: Run unit tests and production build**

Run: `cd web; npm test; npm run build`

Expected: all Vitest suites pass and Vite builds successfully.

- [ ] **Step 3: Run Playwright and capture screenshots**

Run: `cd web; npx playwright install chromium; npm run test:e2e`

Expected: all smoke tests pass at both desktop viewports.

- [ ] **Step 4: Add local operating instructions**

Document `npm install`, `npm run dev`, connection address setup, keyboard controls, emergency stop, and the requirement that the browser and car share a network. Document the later public deployment as out of scope for this phase.

- [ ] **Step 5: Verify the complete workspace**

Run:

```powershell
cd web
npm test
npm run build
npm run test:e2e
cd ..
git diff --check
git status --short
```

Expected: tests and build exit 0; no whitespace errors; only intended files are changed.

- [ ] **Step 6: Commit**

```bash
git add README.md web
git commit -m "docs(web): add desktop client runbook and QA"
```
