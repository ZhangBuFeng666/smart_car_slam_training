(() => {
  "use strict";

  const canvas = document.getElementById("stage-canvas");
  const prefersReducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)");
  const config = {
    autoRotationDurationMillis: 8000,
    resumeDelayMillis: 900,
    dragDegreesPerPixel: 0.58,
  };
  const displayRollRadians = 0;
  const detailCounts = {
    wheels: 0,
    mecanumRollers: 0,
    sensorModules: 0,
    antennas: 0,
    headlights: 0,
    depthCameraLenses: 0,
    displays: 0,
    grilleBars: 0,
  };

  let active = document.visibilityState !== "hidden";
  let connected = false;
  let themeMode = "dark";
  let dragging = false;
  let loaded = false;
  let failed = false;
  let frameCount = 0;
  let yawRadians = Math.PI * -0.18;
  let dragStartX = 0;
  let dragStartYaw = 0;
  let resumeAt = 0;
  let previousFrameTime = performance.now();
  let animationFrame = null;
  let modelRoot = null;
  let partCount = 0;
  let modelSize = { x: 0, y: 0, z: 0 };
  const modelCenter = new THREE.Vector3(0, 0.18, 0);
  let modelRadius = 0.3;
  const cameraDirection = new THREE.Vector3(0.68, 0.32, 0.78).normalize();

  const renderer = new THREE.WebGLRenderer({
    canvas,
    alpha: true,
    antialias: true,
    powerPreference: "high-performance",
    preserveDrawingBuffer: true,
  });
  renderer.setClearColor(0x000000, 0);
  renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
  renderer.outputEncoding = THREE.sRGBEncoding;
  renderer.toneMapping = THREE.ACESFilmicToneMapping;
  renderer.toneMappingExposure = 0.78;
  renderer.shadowMap.enabled = true;
  renderer.shadowMap.type = THREE.PCFSoftShadowMap;

  const scene = new THREE.Scene();
  const camera = new THREE.PerspectiveCamera(31, 1, 0.01, 5);
  camera.position.set(0.68, 0.5, 0.78);
  camera.lookAt(modelCenter);

  scene.add(new THREE.HemisphereLight(0xe3e9e5, 0x080b0a, 0.78));
  scene.add(new THREE.AmbientLight(0xc7d0ca, 0.3));

  const keyLight = new THREE.DirectionalLight(0xffffff, 1.45);
  keyLight.position.set(-0.45, 0.78, 0.58);
  keyLight.castShadow = true;
  keyLight.shadow.mapSize.set(1024, 1024);
  keyLight.shadow.camera.left = -0.5;
  keyLight.shadow.camera.right = 0.5;
  keyLight.shadow.camera.top = 0.5;
  keyLight.shadow.camera.bottom = -0.5;
  keyLight.shadow.camera.near = 0.1;
  keyLight.shadow.camera.far = 2;
  scene.add(keyLight);

  const rimLight = new THREE.DirectionalLight(0xd5bb72, 1.2);
  rimLight.position.set(0.58, 0.42, -0.52);
  scene.add(rimLight);

  const coolFill = new THREE.DirectionalLight(0x75c7bc, 0.34);
  coolFill.position.set(-0.4, 0.24, -0.48);
  scene.add(coolFill);

  const floorShadow = new THREE.Mesh(
    new THREE.PlaneGeometry(0.82, 0.82),
    new THREE.ShadowMaterial({ color: 0x000000, opacity: 0.38 })
  );
  floorShadow.rotation.x = -Math.PI / 2;
  floorShadow.position.y = -0.003;
  floorShadow.receiveShadow = true;
  scene.add(floorShadow);

  const radarGroup = new THREE.Group();
  radarGroup.position.y = -0.001;
  scene.add(radarGroup);

  const radarMaterials = [];
  const makeRadarMaterial = (opacity) => {
    const material = new THREE.MeshBasicMaterial({
      color: 0xc6b57b,
      transparent: true,
      opacity,
      side: THREE.DoubleSide,
      depthWrite: false,
    });
    radarMaterials.push({ material, opacity });
    return material;
  };

  [0.19, 0.27, 0.35].forEach((radius, index) => {
    const ring = new THREE.Mesh(
      new THREE.RingGeometry(radius - 0.0012, radius + 0.0012, 96),
      makeRadarMaterial(0.13 - index * 0.02)
    );
    ring.rotation.x = -Math.PI / 2;
    radarGroup.add(ring);
  });

  const axisPoints = [
    new THREE.Vector3(-0.39, 0, 0),
    new THREE.Vector3(0.39, 0, 0),
    new THREE.Vector3(0, 0, -0.39),
    new THREE.Vector3(0, 0, 0.39),
  ];
  radarGroup.add(
    new THREE.LineSegments(
      new THREE.BufferGeometry().setFromPoints(axisPoints),
      new THREE.LineBasicMaterial({ color: 0xc6b57b, transparent: true, opacity: 0.08 })
    )
  );
  const sweepPivot = new THREE.Group();
  sweepPivot.position.y = 0.003;
  radarGroup.add(sweepPivot);
  const sweepLineMaterial = new THREE.LineBasicMaterial({
    color: 0xc6b57b,
    transparent: true,
    opacity: 0.42,
  });
  radarMaterials.push({ material: sweepLineMaterial, opacity: 0.42 });
  const sweepLine = new THREE.Line(
    new THREE.BufferGeometry().setFromPoints([
      new THREE.Vector3(0.055, 0, 0),
      new THREE.Vector3(0.35, 0, 0),
    ]),
    sweepLineMaterial
  );
  sweepPivot.add(sweepLine);
  const sweepTip = new THREE.Mesh(
    new THREE.SphereGeometry(0.005, 12, 8),
    makeRadarMaterial(0.62)
  );
  sweepTip.position.x = 0.35;
  sweepPivot.add(sweepTip);

  const materials = {
    shell: new THREE.MeshStandardMaterial({ color: 0x0c0e0d, roughness: 0.72, metalness: 0.38 }),
    shellDark: new THREE.MeshStandardMaterial({ color: 0x040605, roughness: 0.82, metalness: 0.22 }),
    graphite: new THREE.MeshStandardMaterial({ color: 0x171a19, roughness: 0.62, metalness: 0.5 }),
    edge: new THREE.MeshStandardMaterial({ color: 0x656b68, roughness: 0.34, metalness: 0.86 }),
    silver: new THREE.MeshStandardMaterial({ color: 0xaeb6b1, roughness: 0.28, metalness: 0.94 }),
    tire: new THREE.MeshStandardMaterial({ color: 0x070908, roughness: 0.94, metalness: 0.04 }),
    roller: new THREE.MeshStandardMaterial({ color: 0x2b302e, roughness: 0.82, metalness: 0.2 }),
    glass: new THREE.MeshStandardMaterial({ color: 0x182322, roughness: 0.12, metalness: 0.4 }),
    lens: new THREE.MeshStandardMaterial({
      color: 0xeef8f4,
      emissive: 0x98cabc,
      emissiveIntensity: 0.42,
      roughness: 0.12,
      metalness: 0.1,
    }),
    gold: new THREE.MeshStandardMaterial({ color: 0xb99b50, roughness: 0.34, metalness: 0.82 }),
    red: new THREE.MeshStandardMaterial({ color: 0x9e2b26, emissive: 0x4f0806, roughness: 0.45 }),
    blue: new THREE.MeshStandardMaterial({ color: 0x17345c, emissive: 0x071c3e, roughness: 0.24 }),
  };
  const statusMaterial = new THREE.MeshStandardMaterial({
    color: 0xc6b57b,
    emissive: 0x594414,
    emissiveIntensity: 0.8,
    roughness: 0.22,
  });

  function mesh(parent, geometry, material, position, rotation, name) {
    const item = new THREE.Mesh(geometry, material);
    item.position.set(position[0], position[1], position[2]);
    item.rotation.set(rotation[0], rotation[1], rotation[2]);
    item.name = name;
    item.castShadow = true;
    item.receiveShadow = true;
    parent.add(item);
    return item;
  }

  function box(parent, size, material, position, rotation = [0, 0, 0], name = "box") {
    return mesh(parent, new THREE.BoxGeometry(size[0], size[1], size[2]), material, position, rotation, name);
  }

  function cylinder(parent, radius, length, material, position, rotation = [0, 0, 0], segments = 24, name = "cylinder") {
    return mesh(
      parent,
      new THREE.CylinderGeometry(radius, radius, length, segments),
      material,
      position,
      rotation,
      name
    );
  }

  function rodBetween(parent, start, end, radius, material, name) {
    const direction = end.clone().sub(start);
    const midpoint = start.clone().add(end).multiplyScalar(0.5);
    const rod = cylinder(parent, radius, direction.length(), material, [midpoint.x, midpoint.y, midpoint.z], [0, 0, 0], 12, name);
    rod.quaternion.setFromUnitVectors(new THREE.Vector3(0, 1, 0), direction.normalize());
    return rod;
  }

  function addMecanumWheel(parent, x, z, chirality) {
    const wheel = new THREE.Group();
    wheel.position.set(x, 0.063, z);
    parent.add(wheel);
    cylinder(wheel, 0.05, 0.052, materials.tire, [0, 0, 0], [Math.PI / 2, 0, 0], 32, "wheel-core");
    cylinder(wheel, 0.035, 0.055, materials.graphite, [0, 0, 0], [Math.PI / 2, 0, 0], 28, "wheel-rim");
    cylinder(wheel, 0.019, 0.058, materials.silver, [0, 0, 0], [Math.PI / 2, 0, 0], 24, "wheel-hub");
    detailCounts.wheels += 1;
    for (let index = 0; index < 10; index += 1) {
      const angle = (index / 10) * Math.PI * 2;
      const roller = cylinder(
        wheel,
        0.0085,
        0.052,
        materials.roller,
        [Math.cos(angle) * 0.05, Math.sin(angle) * 0.05, 0],
        [0, 0, 0],
        10,
        "mecanum-roller"
      );
      const rollerDirection = new THREE.Vector3(
        -Math.sin(angle) * 0.85,
        Math.cos(angle) * 0.85,
        chirality * 0.52
      ).normalize();
      roller.quaternion.setFromUnitVectors(new THREE.Vector3(0, 1, 0), rollerDirection);
      detailCounts.mecanumRollers += 1;
    }
  }

  function dashboardTexture() {
    const textureCanvas = document.createElement("canvas");
    textureCanvas.width = 512;
    textureCanvas.height = 320;
    const context = textureCanvas.getContext("2d");
    context.fillStyle = "#07101b";
    context.fillRect(0, 0, 512, 320);
    context.fillStyle = "#183e72";
    context.fillRect(22, 24, 468, 44);
    context.fillStyle = "#80a9d8";
    context.font = "bold 22px Arial";
    context.fillText("ICAR PATROL", 42, 54);
    context.strokeStyle = "#2f79ba";
    context.lineWidth = 5;
    context.beginPath();
    context.moveTo(28, 244);
    context.lineTo(104, 190);
    context.lineTo(182, 222);
    context.lineTo(262, 128);
    context.lineTo(348, 174);
    context.lineTo(480, 94);
    context.stroke();
    [42, 174, 306].forEach((x, index) => {
      context.fillStyle = ["#4bbf9b", "#d2a847", "#4e80bc"][index];
      context.fillRect(x, 88, 96, 18);
      context.fillStyle = "#18314f";
      context.fillRect(x, 116, 96, 44);
    });
    const texture = new THREE.CanvasTexture(textureCanvas);
    texture.encoding = THREE.sRGBEncoding;
    return texture;
  }

  function logoTexture() {
    const textureCanvas = document.createElement("canvas");
    textureCanvas.width = 512;
    textureCanvas.height = 128;
    const context = textureCanvas.getContext("2d");
    context.clearRect(0, 0, 512, 128);
    context.fillStyle = "#e7ece9";
    context.textAlign = "center";
    context.font = "700 42px Arial";
    context.fillText("HOPERUN", 256, 52);
    context.fillStyle = "#b99b50";
    context.font = "500 24px Arial";
    context.fillText("ICAR PRO", 256, 92);
    const texture = new THREE.CanvasTexture(textureCanvas);
    texture.encoding = THREE.sRGBEncoding;
    return texture;
  }

  function buildHopeRunCar() {
    const root = new THREE.Group();
    root.name = "hoperun-education-car";
    addMecanumWheel(root, 0.145, 0.145, 1);
    addMecanumWheel(root, 0.145, -0.145, -1);
    addMecanumWheel(root, -0.145, 0.145, -1);
    addMecanumWheel(root, -0.145, -0.145, 1);

    box(root, [0.31, 0.034, 0.225], materials.shellDark, [0, 0.09, 0], [0, 0, 0], "lower-frame");
    box(root, [0.335, 0.052, 0.19], materials.shell, [-0.005, 0.113, 0], [0, 0, 0], "lower-body");
    box(root, [0.275, 0.055, 0.028], materials.graphite, [-0.018, 0.105, 0.128], [0, 0, 0], "right-skirt");
    box(root, [0.275, 0.055, 0.028], materials.graphite, [-0.018, 0.105, -0.128], [0, 0, 0], "left-skirt");
    box(root, [0.335, 0.016, 0.245], materials.edge, [0, 0.145, 0], [0, 0, 0], "mid-deck-edge");
    box(root, [0.31, 0.018, 0.225], materials.shellDark, [0, 0.157, 0], [0, 0, 0], "mid-deck");

    box(root, [0.028, 0.098, 0.228], materials.graphite, [0.187, 0.104, 0], [0, 0, 0], "front-fascia");
    box(root, [0.01, 0.083, 0.198], materials.shellDark, [0.204, 0.106, 0], [0, 0, 0], "front-grille");
    for (let index = -4; index <= 4; index += 1) {
      box(root, [0.008, 0.061, 0.006], materials.edge, [0.211, 0.108, index * 0.014], [0, 0, 0], "grille-bar");
      detailCounts.grilleBars += 1;
    }
    [-0.086, 0.086].forEach((z) => {
      cylinder(root, 0.021, 0.012, materials.silver, [0.215, 0.112, z], [0, 0, Math.PI / 2], 24, "headlight-ring");
      cylinder(root, 0.014, 0.014, materials.lens, [0.222, 0.112, z], [0, 0, Math.PI / 2], 24, "headlight-lens");
      detailCounts.headlights += 1;
    });
    for (let index = -3; index <= 3; index += 1) {
      box(root, [0.018, 0.013, 0.018], materials.shellDark, [0.198, 0.047, index * 0.027], [0, 0, 0], "front-bumper-tooth");
    }
    box(root, [0.026, 0.087, 0.22], materials.graphite, [-0.187, 0.108, 0], [0, 0, 0], "rear-fascia");
    [-0.045, 0, 0.045].forEach((z, index) => {
      cylinder(root, index === 1 ? 0.009 : 0.006, 0.012, index === 1 ? materials.red : materials.edge, [-0.205, 0.111, z], [0, 0, Math.PI / 2], 18, "rear-control");
    });

    const moduleX = [-0.078, -0.026, 0.026, 0.078];
    const moduleColors = [materials.red, materials.gold, materials.blue, statusMaterial];
    [-0.077, 0.077].forEach((z, row) => {
      moduleX.forEach((x, index) => {
        box(root, [0.043, 0.048, 0.046], materials.shellDark, [x, 0.188, z], [0, 0, 0], "sensor-module");
        box(root, [0.034, 0.004, 0.034], moduleColors[(index + row) % moduleColors.length], [x, 0.214, z], [0, 0, 0], "sensor-module-cap");
        box(root, [0.016, 0.014, 0.004], materials.graphite, [x, 0.188, z + (z > 0 ? 0.024 : -0.024)], [0, 0, 0], "sensor-module-mark");
        detailCounts.sensorModules += 1;
      });
    });
    [-0.112, 0.112].forEach((z) => {
      box(root, [0.245, 0.012, 0.012], materials.edge, [0, 0.218, z], [0, 0, 0], "sensor-rail");
      for (let index = -5; index <= 5; index += 1) {
        cylinder(root, 0.0035, 0.005, statusMaterial, [index * 0.021, 0.226, z], [0, 0, 0], 10, "rail-led");
      }
    });

    const towerShape = new THREE.Shape();
    towerShape.moveTo(-0.106, 0.205);
    towerShape.lineTo(0.084, 0.205);
    towerShape.lineTo(0.105, 0.274);
    towerShape.lineTo(0.072, 0.307);
    towerShape.lineTo(-0.076, 0.307);
    towerShape.lineTo(-0.111, 0.278);
    towerShape.closePath();
    const towerGeometry = new THREE.ExtrudeGeometry(towerShape, {
      depth: 0.142,
      bevelEnabled: true,
      bevelSegments: 2,
      bevelSize: 0.006,
      bevelThickness: 0.005,
      steps: 1,
    });
    towerGeometry.translate(0, 0, -0.071);
    mesh(root, towerGeometry, materials.shell, [0, 0, 0], [0, 0, 0], "upper-tower");
    [-0.073, 0.073].forEach((z) => {
      for (let index = 0; index < 3; index += 1) {
        box(root, [0.051, 0.005, 0.005], materials.edge, [0.042, 0.253 + index * 0.017, z], [0, 0, -0.08], "tower-vent");
      }
    });

    box(root, [0.012, 0.1, 0.138], materials.graphite, [-0.111, 0.256, 0], [0, 0, displayRollRadians], "display-bezel");
    mesh(root, new THREE.PlaneGeometry(0.118, 0.078), new THREE.MeshBasicMaterial({ map: dashboardTexture() }), [-0.119, 0.258, 0], [0, -Math.PI / 2, displayRollRadians], "rear-display");
    detailCounts.displays += 1;

    box(root, [0.04, 0.026, 0.195], materials.graphite, [0.096, 0.311, 0], [0, 0, 0], "depth-camera-bar");
    box(root, [0.006, 0.018, 0.177], materials.glass, [0.118, 0.311, 0], [0, 0, 0], "depth-camera-glass");
    [-0.061, 0, 0.061].forEach((z, index) => {
      cylinder(root, index === 1 ? 0.0075 : 0.006, 0.007, index === 1 ? materials.lens : materials.glass, [0.124, 0.311, z], [0, 0, Math.PI / 2], 20, "depth-camera-lens");
      detailCounts.depthCameraLenses += 1;
    });
    cylinder(root, 0.034, 0.018, materials.shellDark, [-0.025, 0.318, 0], [0, 0, 0], 32, "lidar-base");
    cylinder(root, 0.029, 0.024, materials.graphite, [-0.025, 0.337, 0], [0, 0, 0], 32, "lidar-head");
    cylinder(root, 0.017, 0.004, materials.glass, [-0.025, 0.351, 0], [0, 0, 0], 28, "lidar-window");
    [-0.053, 0.053].forEach((z) => {
      rodBetween(root, new THREE.Vector3(-0.055, 0.302, z), new THREE.Vector3(-0.102, 0.397, z * 1.28), 0.0042, materials.graphite, "antenna");
      cylinder(root, 0.006, 0.018, materials.shellDark, [-0.055, 0.307, z], [0, 0, 0], 14, "antenna-base");
      detailCounts.antennas += 1;
    });

    const sideLogoMaterial = new THREE.MeshBasicMaterial({
      map: logoTexture(),
      transparent: true,
      alphaTest: 0.02,
      side: THREE.DoubleSide,
    });
    mesh(root, new THREE.PlaneGeometry(0.105, 0.026), sideLogoMaterial, [-0.04, 0.113, 0.143], [0, 0, 0], "right-logo");
    mesh(root, new THREE.PlaneGeometry(0.105, 0.026), sideLogoMaterial, [-0.04, 0.113, -0.143], [0, Math.PI, 0], "left-logo");
    return root;
  }

  function normalizeRadians(value) {
    const fullTurn = Math.PI * 2;
    return ((value % fullTurn) + fullTurn) % fullTurn;
  }

  function fitCameraToModel() {
    const verticalHalfFov = THREE.MathUtils.degToRad(camera.fov * 0.5);
    const horizontalHalfFov = Math.atan(Math.tan(verticalHalfFov) * camera.aspect);
    const limitingHalfFov = Math.min(verticalHalfFov, horizontalHalfFov);
    const distance = (modelRadius / Math.sin(limitingHalfFov)) * 0.76;
    camera.position.copy(modelCenter).addScaledVector(cameraDirection, distance);
    camera.lookAt(modelCenter);
  }

  function resizeRenderer() {
    const width = Math.max(1, canvas.clientWidth);
    const height = Math.max(1, canvas.clientHeight);
    renderer.setSize(width, height, false);
    camera.aspect = width / height;
    if (loaded) fitCameraToModel();
    camera.updateProjectionMatrix();
    renderOnce();
  }

  function renderOnce() {
    if (!loaded || failed) return;
    modelRoot.rotation.y = yawRadians;
    renderer.render(scene, camera);
    frameCount += 1;
  }

  function animate(now) {
    animationFrame = null;
    if (!active || failed) return;
    const deltaMillis = Math.min(50, Math.max(0, now - previousFrameTime));
    previousFrameTime = now;
    if (loaded && !dragging && now >= resumeAt && !prefersReducedMotion.matches) {
      yawRadians = normalizeRadians(yawRadians + (deltaMillis / config.autoRotationDurationMillis) * Math.PI * 2);
    }
    if (loaded) {
      sweepPivot.rotation.y = normalizeRadians(sweepPivot.rotation.y - deltaMillis * 0.00072);
      renderOnce();
    }
    animationFrame = requestAnimationFrame(animate);
  }

  function scheduleAnimation() {
    if (!active || failed || animationFrame !== null) return;
    previousFrameTime = performance.now();
    animationFrame = requestAnimationFrame(animate);
  }

  function setActive(value) {
    active = Boolean(value);
    if (active) scheduleAnimation();
    else if (animationFrame !== null) {
      cancelAnimationFrame(animationFrame);
      animationFrame = null;
    }
  }

  function setConnected(value) {
    connected = Boolean(value);
    applyThemeAccent();
    renderOnce();
  }

  function applyThemeAccent() {
    const color = connected ? 0x4fe1b6 : themeMode === "light" ? 0x62b9e8 : 0xc6b57b;
    radarMaterials.forEach(({ material, opacity }) => {
      material.color.setHex(color);
      material.opacity = connected ? Math.min(opacity * 1.5, 0.2) : opacity;
    });
    statusMaterial.color.setHex(color);
    statusMaterial.emissive.setHex(connected ? 0x185c49 : themeMode === "light" ? 0x174c66 : 0x594414);
  }

  function setTheme(value) {
    themeMode = value === "light" ? "light" : "dark";
    renderer.toneMappingExposure = themeMode === "light" ? 0.92 : 0.78;
    floorShadow.material.opacity = themeMode === "light" ? 0.18 : 0.38;
    rimLight.color.setHex(themeMode === "light" ? 0x80c9ed : 0xd5bb72);
    applyThemeAccent();
    renderOnce();
  }

  function configure(values) {
    if (!values || typeof values !== "object") return;
    if (Number.isFinite(values.autoRotationDurationMillis)) {
      config.autoRotationDurationMillis = Math.max(2000, values.autoRotationDurationMillis);
    }
    if (Number.isFinite(values.resumeDelayMillis)) {
      config.resumeDelayMillis = Math.max(0, values.resumeDelayMillis);
    }
  }

  function pointerDown(event) {
    if (!loaded || event.button > 0) return;
    dragging = true;
    dragStartX = event.clientX;
    dragStartYaw = yawRadians;
    if (canvas.setPointerCapture) canvas.setPointerCapture(event.pointerId);
  }

  function pointerMove(event) {
    if (!dragging) return;
    const deltaDegrees = (event.clientX - dragStartX) * config.dragDegreesPerPixel;
    yawRadians = normalizeRadians(dragStartYaw + THREE.MathUtils.degToRad(deltaDegrees));
    renderOnce();
  }

  function pointerUp(event) {
    if (!dragging) return;
    dragging = false;
    resumeAt = performance.now() + config.resumeDelayMillis;
    if (canvas.releasePointerCapture) canvas.releasePointerCapture(event.pointerId);
  }

  canvas.addEventListener("pointerdown", pointerDown);
  canvas.addEventListener("pointermove", pointerMove);
  canvas.addEventListener("pointerup", pointerUp);
  canvas.addEventListener("pointercancel", pointerUp);
  window.addEventListener("resize", resizeRenderer);
  document.addEventListener("visibilitychange", () => setActive(document.visibilityState !== "hidden"));
  if (prefersReducedMotion.addEventListener) prefersReducedMotion.addEventListener("change", renderOnce);
  else if (prefersReducedMotion.addListener) prefersReducedMotion.addListener(renderOnce);

  window.ICar3DStage = { configure, setActive, setConnected, setTheme };
  window.__icar3dDiagnostics = () => ({
    loaded,
    failed,
    connected,
    frameCount,
    yawDegrees: THREE.MathUtils.radToDeg(normalizeRadians(yawRadians)),
    modelY: modelRoot ? modelRoot.position.y : null,
    rendererWidth: canvas.clientWidth,
    rendererHeight: canvas.clientHeight,
    modelVariant: "hoperun-education-car",
    displayRollDegrees: THREE.MathUtils.radToDeg(displayRollRadians),
    radarSweepStyle: "radial-line",
    partCount,
    detailCounts: { ...detailCounts },
    modelSize: { ...modelSize },
  });

  try {
    modelRoot = buildHopeRunCar();
    modelRoot.position.set(0, 0, 0);
    modelRoot.rotation.y = yawRadians;
    scene.add(modelRoot);
    modelRoot.traverse((object) => {
      if (object.isMesh) partCount += 1;
    });
    const measuredSize = new THREE.Vector3();
    const bounds = new THREE.Box3().setFromObject(modelRoot);
    bounds.getSize(measuredSize);
    const sphere = new THREE.Sphere();
    bounds.getBoundingSphere(sphere);
    modelCenter.copy(sphere.center);
    modelRadius = sphere.radius;
    modelSize = { x: measuredSize.x, y: measuredSize.y, z: measuredSize.z };
    loaded = true;
    document.documentElement.classList.add("stage-ready");
    resizeRenderer();
    setConnected(connected);
    renderOnce();
    scheduleAnimation();
    if (window.AndroidStage) window.AndroidStage.onReady();
  } catch (error) {
    failed = true;
    console.error("Unable to build the HopeRun iCar model", error);
    if (window.AndroidStage) window.AndroidStage.onFailed();
  }
})();
