/* ReflectiveCard shader and studio reflection from the supplied React Bits source.
   Native DOM adapter for the five feature cards; custom photo content is preserved. */
export function mountReflections(root, scope, carousel) {
const requestAnimationFrame=scope.frame;
const cancelAnimationFrame=scope.cancelFrame;
'use strict';
const VIEW_DISTANCE = 900;
const OVERSCAN = 1.2;
const INTRO_MS = 1400;
const FADE_SECONDS = 0.35;

const VERTEX = `#version 300 es
in vec2 aPosition;
out vec2 vUv;
void main() {
  vUv = aPosition * 0.5 + 0.5;
  gl_Position = vec4(aPosition, 0.0, 1.0);
}`;

const CAPTURE = `#version 300 es
precision highp float;
in vec2 vUv;
out vec4 fragColor;
uniform sampler2D uVideo;
uniform sampler2D uCam;
uniform vec4 uVideoMap;
uniform vec4 uCamMap;
uniform vec2 uWeights;
uniform vec2 uLod;
float level(sampler2D source) {
  vec3 mean = pow(textureLod(source, vec2(0.5), 14.0).rgb, vec3(2.2));
  return pow(dot(mean, vec3(0.2126, 0.7152, 0.0722)), 1.0 / 2.2);
}
void main() {
  vec3 color = vec3(0.0);
  float mean = 0.0;
  if (uWeights.x > 0.0) {
    color += textureLod(uVideo, vUv * uVideoMap.xy + uVideoMap.zw, uLod.x).rgb * uWeights.x;
    mean += level(uVideo) * uWeights.x;
  }
  if (uWeights.y > 0.0) {
    color += textureLod(uCam, vUv * uCamMap.xy + uCamMap.zw, uLod.y).rgb * uWeights.y;
    mean += level(uCam) * uWeights.y;
  }
  fragColor = vec4(color, mean);
}`;

const BLUR = `#version 300 es
precision highp float;
in vec2 vUv;
out vec4 fragColor;
uniform sampler2D uTexture;
uniform vec2 uStep;
uniform float uSigma;
void main() {
  vec4 sum = texture(uTexture, vUv);
  if (uSigma < 0.35) {
    fragColor = sum;
    return;
  }
  float falloff = -0.5 / (uSigma * uSigma);
  int radius = int(min(ceil(uSigma * 2.8), 40.0));
  float total = 1.0;
  for (int i = 1; i <= 40; i++) {
    if (i > radius) break;
    float offset = float(i);
    float weight = exp(offset * offset * falloff);
    sum += (texture(uTexture, vUv + uStep * offset) + texture(uTexture, vUv - uStep * offset)) * weight;
    total += 2.0 * weight;
  }
  fragColor = sum / total;
}`;

const PLATE = `#version 300 es
precision highp float;
in vec2 vUv;
out vec4 fragColor;

uniform sampler2D uEnv;
uniform vec2 uEnvSize;
uniform vec2 uSize;
uniform float uRadius;
uniform float uScale;
uniform mat3 uRotate;
uniform float uCapture;
uniform float uBlur;
uniform float uRough;
uniform float uMetal;
uniform vec3 uTint;
uniform float uGray;
uniform float uWarp;
uniform float uWarpScale;
uniform float uShine;
uniform float uBevel;
uniform float uGlare;
uniform vec2 uPointer;
uniform float uTheme;

const float VIEW = ${VIEW_DISTANCE.toFixed(1)};
const float OVERSCAN = ${OVERSCAN.toFixed(2)};
const float CONTENT = 46.0;

float hash(vec2 p) {
  vec3 p3 = fract(vec3(p.xyx) * 0.1031);
  p3 += dot(p3, p3.yzx + 33.33);
  return fract((p3.x + p3.y) * p3.z);
}

vec2 hash2(vec2 p) {
  vec3 p3 = fract(vec3(p.xyx) * vec3(0.1031, 0.103, 0.0973));
  p3 += dot(p3, p3.yzx + 33.33);
  return fract((p3.xx + p3.yz) * p3.zy) * 2.0 - 1.0;
}

vec3 gradientNoise(vec2 p) {
  vec2 i = floor(p);
  vec2 f = fract(p);
  vec2 u = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);
  vec2 du = 30.0 * f * f * (f * (f - 2.0) + 1.0);
  vec2 ga = hash2(i);
  vec2 gb = hash2(i + vec2(1.0, 0.0));
  vec2 gc = hash2(i + vec2(0.0, 1.0));
  vec2 gd = hash2(i + vec2(1.0, 1.0));
  float va = dot(ga, f);
  float vb = dot(gb, f - vec2(1.0, 0.0));
  float vc = dot(gc, f - vec2(0.0, 1.0));
  float vd = dot(gd, f - vec2(1.0, 1.0));
  float k = va - vb - vc + vd;
  float value = va + u.x * (vb - va) + u.y * (vc - va) + u.x * u.y * k;
  vec2 slope = ga + u.x * (gb - ga) + u.y * (gc - ga) + u.x * u.y * (ga - gb - gc + gd) + du * (u.yx * k + vec2(vb, vc) - va);
  return vec3(value, slope);
}

float valueNoise(vec2 p) {
  vec2 i = floor(p);
  vec2 f = fract(p);
  vec2 u = f * f * (3.0 - 2.0 * f);
  float a = hash(i);
  float b = hash(i + vec2(1.0, 0.0));
  float c = hash(i + vec2(0.0, 1.0));
  float d = hash(i + vec2(1.0, 1.0));
  return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

float erf(float x) {
  float x2 = x * x;
  return sign(x) * sqrt(1.0 - exp(-x2 * (1.2732395 + 0.147 * x2) / (1.0 + 0.147 * x2)));
}

float band(float x, float halfWidth, float sigma) {
  float s = 1.4142136 * max(sigma, 0.0001);
  return 0.5 * (erf((x + halfWidth) / s) - erf((x - halfWidth) / s));
}

float strip(vec2 q, vec2 center, vec2 dir, float halfWidth, vec2 sigma) {
  vec2 normal = vec2(-dir.y, dir.x);
  return band(dot(q - center, normal), halfWidth, length(sigma * normal));
}

const vec2 SWEEP = vec2(0.5, 0.86);
const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);

float softbox(vec2 q, vec2 sigma) {
  float key = strip(q, vec2(-0.1, -0.16), normalize(SWEEP), 0.022, sigma + 0.018);
  float top = band(q.y + 0.6, 0.12, sigma.y + 0.05);
  float side = band(q.x - 0.46, 0.05, sigma.x + 0.05);
  return key + top * 0.8 + side * 0.35;
}

float room(vec2 q, vec2 sigma) {
  float fall = smoothstep(-0.55, 0.5, q.y);
  float flag = strip(q, vec2(0.24, -0.02), normalize(SWEEP), 0.09, sigma + 0.08);
  return mix(0.85, 0.12, fall) * (1.0 - 0.6 * flag);
}

vec4 sampleEnv(vec2 uv) {
  vec2 st = uv * uEnvSize - 0.5;
  vec2 i = floor(st);
  vec2 f = st - i;
  vec2 f2 = f * f;
  vec2 f3 = f2 * f;
  vec2 w0 = (1.0 - 3.0 * f + 3.0 * f2 - f3) / 6.0;
  vec2 w1 = (4.0 - 6.0 * f2 + 3.0 * f3) / 6.0;
  vec2 w2 = (1.0 + 3.0 * f + 3.0 * f2 - 3.0 * f3) / 6.0;
  vec2 w3 = f3 / 6.0;
  vec2 g0 = w0 + w1;
  vec2 g1 = w2 + w3;
  vec2 c0 = (i + 0.5 + w1 / g0 - 1.0) / uEnvSize;
  vec2 c1 = (i + 0.5 + w3 / g1 + 1.0) / uEnvSize;
  vec4 a = texture(uEnv, c0) * g0.x + texture(uEnv, vec2(c1.x, c0.y)) * g1.x;
  vec4 b = texture(uEnv, vec2(c0.x, c1.y)) * g0.x + texture(uEnv, c1) * g1.x;
  return a * g0.y + b * g1.y;
}

vec3 toLinear(vec3 c) {
  return pow(max(c, 0.0), vec3(2.2));
}

void main() {
  vec2 p = vec2(vUv.x - 0.5, 0.5 - vUv.y) * uSize;
  vec2 halfSize = uSize * 0.5;
  float r = min(uRadius, min(halfSize.x, halfSize.y));
  vec2 corner = abs(p) - halfSize + r;
  vec2 side = vec2(p.x >= 0.0 ? 1.0 : -1.0, p.y >= 0.0 ? 1.0 : -1.0);
  float d;
  vec2 outward;
  if (corner.x > 0.0 && corner.y > 0.0) {
    d = length(corner) - r;
    outward = normalize(corner) * side;
  } else if (corner.x > corner.y) {
    d = corner.x - r;
    outward = vec2(side.x, 0.0);
  } else {
    d = corner.y - r;
    outward = vec2(0.0, side.y);
  }
  float coverage = clamp(0.5 - d * uScale, 0.0, 1.0);

  float chamferWidth = min(uBevel, max(r, 1.0));
  float chamfer = chamferWidth > 0.01 ? 1.0 - smoothstep(0.0, chamferWidth, -d) : 0.0;
  vec2 slope = outward * chamfer;

  vec2 wp = p / (220.0 * uWarpScale);
  vec3 w1 = gradientNoise(wp);
  vec3 w2 = gradientNoise(wp * 2.07 + 13.7);
  vec2 ripple = (w1.yz + w2.yz * 0.5) * uWarp;
  slope += ripple * 0.008;

  float grain = valueNoise(vec2(p.x * 0.008, p.y * 0.55)) * 0.45 + valueNoise(vec2(p.x * 0.019 + 3.7, p.y * 0.23)) * 0.35 + valueNoise(vec2(p.x * 0.004 + 9.2, p.y * 0.09)) * 0.2;
  float brush = uRough * uRough;
  slope.y += (grain - 0.5) * brush * 0.004;

  vec3 normal = uRotate * normalize(vec3(slope, 1.0));
  vec3 position = uRotate * vec3(p, 0.0);
  vec3 view = normalize(position - vec3(0.0, 0.0, VIEW));
  vec3 bounce = reflect(view, normal);
  vec2 q = bounce.xy / max(bounce.z, 0.2);

  vec2 footprint = fwidth(q);
  vec2 sigma = sqrt(vec2(uBlur / VIEW) * vec2(uBlur / VIEW) + footprint * footprint * 0.5);
  sigma += vec2(0.004, 0.05) * uRough;

  vec2 shift = clamp((q - p / VIEW) * CONTENT + ripple * 10.0, -uSize, uSize);
  vec2 cp = (p + shift) / (uSize * OVERSCAN);
  vec4 reflected = uCapture > 0.0 ? sampleEnv(vec2(0.5 + cp.x, 0.5 - cp.y)) : vec4(0.0, 0.0, 0.0, 1.0);
  vec3 captured = toLinear(reflected.rgb) * (0.5 / max(pow(reflected.a, 2.2), 0.02));
  captured = mix(vec3(dot(captured, LUMA)), captured, 1.0 - uGray);
  captured = pow(max(captured * 1.2, 0.0), vec3(mix(2.0, 1.5, uTheme)));

  vec3 scene = mix(vec3(room(q, sigma)), captured, uCapture * (1.0 - chamfer) * mix(1.0, 0.6, uTheme));
  scene *= 1.0 + (grain - 0.5) * brush * 0.3;

  vec2 toPointer = (uPointer - p) / (120.0 + uBlur * 4.0);
  vec2 spread = toPointer * vec2(1.0, 1.0 / (1.0 + 2.5 * uRough));
  float reach = dot(spread, spread);
  float glint = max(1.0 + 3.0 * dot(slope, toPointer), 0.0);
  float glare = (exp(-reach * 1.6) + exp(-reach * 0.35) * 0.15) * glint * uGlare * (1.0 + 0.8 * (grain - 0.5) * brush);
  float highlight = (softbox(q, sigma) * uShine + glare) * mix(0.4, 1.0, uMetal);

  vec3 dark = vec3(0.005, 0.005, 0.006) + uTint * (scene * 0.05 * uMetal + highlight * 0.36);
  vec3 light = uTint * (vec3(0.83, 0.83, 0.85) * (1.0 - 0.45 * uMetal * (1.0 - clamp(scene, 0.0, 1.0))) + highlight * 0.35);
  vec3 color = mix(dark, light, uTheme);
  color = min(color, 0.7) + 0.3 * (1.0 - exp(-max(color - 0.7, 0.0) / 0.3));
  color = pow(clamp(color, 0.0, 1.0), vec3(1.0 / 2.2));

  color += (hash(gl_FragCoord.xy) - 0.5) / 255.0;
  color = clamp(color, 0.0, 1.0);
  fragColor = vec4(color * coverage, coverage);
}`;

const clamp = (value, min, max) => Math.min(max, Math.max(min, value));

const toPx = value => {
  if (typeof value === 'number') return `${value}px`;
  return value;
};

const parseColor = value => {
  const text = String(value || '').trim();
  const hex = /^#?([0-9a-f]{3}|[0-9a-f]{6})$/i.exec(text);
  if (hex) {
    const full = hex[1].length === 3 ? hex[1].replace(/./g, digit => digit + digit) : hex[1];
    return [0, 2, 4].map(index => parseInt(full.slice(index, index + 2), 16) / 255);
  }
  const rgb = /^rgba?\(\s*([\d.]+)[\s,]+([\d.]+)[\s,]+([\d.]+)/i.exec(text);
  if (rgb) return [rgb[1], rgb[2], rgb[3]].map(channel => clamp(parseFloat(channel) / 255, 0, 1));
  return [1, 1, 1];
};

const compile = (gl, type, source) => {
  const shader = gl.createShader(type);
  if (!shader) return null;
  gl.shaderSource(shader, source);
  gl.compileShader(shader);
  if (!gl.getShaderParameter(shader, gl.COMPILE_STATUS)) {
    console.error(gl.getShaderInfoLog(shader));
    gl.deleteShader(shader);
    return null;
  }
  return shader;
};

const link = (gl, fragmentSource) => {
  const vertex = compile(gl, gl.VERTEX_SHADER, VERTEX);
  const fragment = compile(gl, gl.FRAGMENT_SHADER, fragmentSource);
  const program = vertex && fragment ? gl.createProgram() : null;
  if (!vertex || !fragment || !program) {
    if (vertex) gl.deleteShader(vertex);
    if (fragment) gl.deleteShader(fragment);
    return null;
  }
  gl.attachShader(program, vertex);
  gl.attachShader(program, fragment);
  gl.bindAttribLocation(program, 0, 'aPosition');
  gl.linkProgram(program);
  gl.deleteShader(vertex);
  gl.deleteShader(fragment);
  if (!gl.getProgramParameter(program, gl.LINK_STATUS)) {
    console.error(gl.getProgramInfoLog(program));
    gl.deleteProgram(program);
    return null;
  }
  const uniforms = {};
  const count = gl.getProgramParameter(program, gl.ACTIVE_UNIFORMS);
  for (let index = 0; index < count; index++) {
    const info = gl.getActiveUniform(program, index);
    if (info) uniforms[info.name] = gl.getUniformLocation(program, info.name);
  }
  return { program, uniforms };
};

const coverMap = (width, height, aspect, mirror) => {
  const ratio = width / Math.max(1, height);
  const sx = ratio > aspect ? aspect / ratio : 1;
  const sy = ratio > aspect ? 1 : ratio / aspect;
  const u = mirror ? -sx : sx;
  return [u, -sy, 0.5 - u * 0.5, 0.5 + sy * 0.5];
};

const lodFor = (width, height, envWidth, aspect) => {
  const ratio = width / Math.max(1, height);
  const visible = width * (ratio > aspect ? aspect / ratio : 1);
  return Math.max(0, Math.log2(Math.max(1, visible / Math.max(1, envWidth))) - 0.5);
};

const createRenderer = canvas => {
  const gl = canvas.getContext('webgl2', {
    alpha: true,
    premultipliedAlpha: true,
    antialias: false,
    depth: false,
    stencil: false,
    powerPreference: 'default'
  });
  if (!gl) return null;
  const captureProgram = link(gl, CAPTURE);
  const blurProgram = link(gl, BLUR);
  const plateProgram = link(gl, PLATE);
  if (!captureProgram || !blurProgram || !plateProgram) {
    for (const item of [captureProgram, blurProgram, plateProgram]) {
      if (item) gl.deleteProgram(item.program);
    }
    return null;
  }

  const createTexture = (mipmaps, wrap) => {
    const texture = gl.createTexture();
    gl.bindTexture(gl.TEXTURE_2D, texture);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, mipmaps ? gl.LINEAR_MIPMAP_LINEAR : gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, wrap);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, wrap);
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, 1, 1, 0, gl.RGBA, gl.UNSIGNED_BYTE, new Uint8Array([0, 0, 0, 255]));
    return texture;
  };

  const createTarget = () => {
    const texture = createTexture(false, gl.MIRRORED_REPEAT);
    const framebuffer = gl.createFramebuffer();
    gl.bindFramebuffer(gl.FRAMEBUFFER, framebuffer);
    gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, texture, 0);
    gl.bindFramebuffer(gl.FRAMEBUFFER, null);
    return { texture, framebuffer, width: 1, height: 1 };
  };

  const allocate = (target, width, height) => {
    if (target.width === width && target.height === height) return;
    target.width = width;
    target.height = height;
    gl.bindTexture(gl.TEXTURE_2D, target.texture);
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, width, height, 0, gl.RGBA, gl.UNSIGNED_BYTE, null);
  };

  const vao = gl.createVertexArray();
  gl.bindVertexArray(vao);
  const buffer = gl.createBuffer();
  gl.bindBuffer(gl.ARRAY_BUFFER, buffer);
  gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 3, -1, -1, 3]), gl.STATIC_DRAW);
  gl.enableVertexAttribArray(0);
  gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0);
  gl.disable(gl.BLEND);
  gl.disable(gl.DEPTH_TEST);

  const textures = { video: createTexture(true, gl.CLAMP_TO_EDGE), cam: createTexture(true, gl.CLAMP_TO_EDGE) };
  const capture = createTarget();
  const blurX = createTarget();
  const blurY = createTarget();
  const rotation = new Float32Array(9);

  const pass = (target, program) => {
    gl.bindFramebuffer(gl.FRAMEBUFFER, target ? target.framebuffer : null);
    gl.viewport(0, 0, target ? target.width : canvas.width, target ? target.height : canvas.height);
    gl.useProgram(program.program);
    return program.uniforms;
  };

  const bind = (unit, texture) => {
    gl.activeTexture(gl.TEXTURE0 + unit);
    gl.bindTexture(gl.TEXTURE_2D, texture);
  };

  const upload = (key, element) => {
    try {
      gl.bindTexture(gl.TEXTURE_2D, textures[key]);
      gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL, false);
      gl.pixelStorei(gl.UNPACK_PREMULTIPLY_ALPHA_WEBGL, false);
      gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, element);
      gl.generateMipmap(gl.TEXTURE_2D);
      return true;
    } catch {
      return false;
    }
  };

  const renderEnv = ({ width, height, scale, blur, roughness, video, cam }) => {
    const envScale = clamp(2.5 / Math.max(blur, 0.01), 0.12, scale);
    const envWidth = Math.max(8, Math.ceil(width * OVERSCAN * envScale));
    const envHeight = Math.max(8, Math.ceil(height * OVERSCAN * envScale));
    allocate(capture, envWidth, envHeight);
    allocate(blurX, envWidth, envHeight);
    allocate(blurY, envWidth, envHeight);
    const aspect = width / Math.max(1, height);
    const total = 1 - (1 - video.mix) * (1 - cam.mix);
    const camWeight = total > 0 ? cam.mix / total : 0;

    let u = pass(capture, captureProgram);
    bind(0, textures.video);
    bind(1, textures.cam);
    gl.uniform1i(u.uVideo, 0);
    gl.uniform1i(u.uCam, 1);
    gl.uniform4fv(u.uVideoMap, video.ready ? coverMap(video.width, video.height, aspect, false) : [1, 1, 0, 0]);
    gl.uniform4fv(u.uCamMap, cam.ready ? coverMap(cam.width, cam.height, aspect, true) : [1, 1, 0, 0]);
    gl.uniform2f(u.uWeights, video.ready && total > 0 ? 1 - camWeight : 0, cam.ready ? camWeight : 0);
    gl.uniform2f(
      u.uLod,
      video.ready ? lodFor(video.width, video.height, envWidth, aspect) : 0,
      cam.ready ? lodFor(cam.width, cam.height, envWidth, aspect) : 0
    );
    gl.drawArrays(gl.TRIANGLES, 0, 3);

    u = pass(blurX, blurProgram);
    bind(0, capture.texture);
    gl.uniform1i(u.uTexture, 0);
    gl.uniform2f(u.uStep, 1 / envWidth, 0);
    gl.uniform1f(u.uSigma, blur * envScale);
    gl.drawArrays(gl.TRIANGLES, 0, 3);

    u = pass(blurY, blurProgram);
    bind(0, blurX.texture);
    gl.uniform1i(u.uTexture, 0);
    gl.uniform2f(u.uStep, 0, 1 / envHeight);
    gl.uniform1f(u.uSigma, (blur * (1 + 1.5 * roughness) + 10 * roughness) * envScale);
    gl.drawArrays(gl.TRIANGLES, 0, 3);
  };

  const renderPlate = ({ width, height, rotateX, rotateY, capture: amount, glare, pointerX, pointerY, settings }) => {
    const cx = Math.cos(rotateX);
    const sx = Math.sin(rotateX);
    const cy = Math.cos(rotateY);
    const sy = Math.sin(rotateY);
    rotation.set([cy, sx * sy, -cx * sy, 0, cx, sx, sy, -sx * cy, cx * cy]);

    const u = pass(null, plateProgram);
    bind(0, blurY.texture);
    gl.uniform1i(u.uEnv, 0);
    gl.uniform2f(u.uEnvSize, blurY.width, blurY.height);
    gl.uniform2f(u.uSize, width, height);
    gl.uniform1f(u.uRadius, settings.radius);
    gl.uniform1f(u.uScale, canvas.width / Math.max(1, width));
    gl.uniformMatrix3fv(u.uRotate, false, rotation);
    gl.uniform1f(u.uCapture, amount);
    gl.uniform1f(u.uBlur, settings.blurStrength);
    gl.uniform1f(u.uRough, settings.roughness);
    gl.uniform1f(u.uMetal, settings.metalness);
    gl.uniform3fv(u.uTint, settings.tint);
    gl.uniform1f(u.uGray, settings.grayscale);
    gl.uniform1f(u.uWarp, settings.warp);
    gl.uniform1f(u.uWarpScale, settings.warpScale);
    gl.uniform1f(u.uShine, settings.shine);
    gl.uniform1f(u.uBevel, settings.bevel);
    gl.uniform1f(u.uGlare, glare);
    gl.uniform2f(u.uPointer, pointerX, pointerY);
    gl.uniform1f(u.uTheme, settings.theme);
    gl.drawArrays(gl.TRIANGLES, 0, 3);
  };

  const dispose = () => {
    if (gl.isContextLost()) return;
    for (const target of [capture, blurX, blurY]) {
      gl.deleteFramebuffer(target.framebuffer);
      gl.deleteTexture(target.texture);
    }
    gl.deleteTexture(textures.video);
    gl.deleteTexture(textures.cam);
    gl.deleteBuffer(buffer);
    gl.deleteVertexArray(vao);
    for (const item of [captureProgram, blurProgram, plateProgram]) gl.deleteProgram(item.program);
  };

  return { upload, renderEnv, renderPlate, dispose };
};


// One shared studio renderer; each feature keeps its own pointer spring and tilt.
const media=matchMedia('(prefers-reduced-motion: reduce)');
const surface=document.createElement('canvas');
let renderer=null;try{renderer=createRenderer(surface);}catch{}
const roots=[...root.querySelectorAll('.circular-carousel__frame')];
const settings={tint:parseColor('#edf5ff').map(c=>Math.pow(c,2.2)),metalness:1,roughness:.36,blurStrength:9,grayscale:1,warp:.6,warpScale:1,shine:1.2,bevel:2.2,radius:18,theme:0};
let raf=0,last=0,visible=true,force=true;
const instances=roots.map((root,index)=>{
  root.classList.add('reflective-feature');
  const canvas=document.createElement('canvas');canvas.className='reflective-feature__canvas';canvas.setAttribute('aria-hidden','true');root.querySelector('.commerce-art').after(canvas);
  const rim=document.createElement('span');rim.className='reflective-feature__rim';rim.setAttribute('aria-hidden','true');root.append(rim);
  root.dataset.reflectiveRenderer=renderer?'webgl':'fallback';
  return {root,canvas,context:canvas.getContext('2d'),index,x:{p:0,v:0,target:0},y:{p:0,v:0,target:0},light:{p:0,target:0},pointer:{x:0,y:0},width:1,height:1,dirty:true,introStart:null,introDone:media.matches,touchId:null};
});
function reset(item){for(const axis of [item.x,item.y])axis.target=0;item.light.target=0;item.dirty=true;wake();}
function aim(item,event){
  if(media.matches)return;
  if(carousel?.getState().dragging){reset(item);return;}
  item.introDone=true;
  const rect=item.root.getBoundingClientRect(),nx=clamp((event.clientX-rect.left)/rect.width*2-1,-1,1),ny=clamp((event.clientY-rect.top)/rect.height*2-1,-1,1);
  item.x.target=nx;item.y.target=ny;item.light.target=1;item.pointer.x=nx*item.width*.5;item.pointer.y=ny*item.height*.5;
  item.root.style.setProperty('--rf-mx',`${(nx+1)*50}%`);item.root.style.setProperty('--rf-my',`${(ny+1)*50}%`);item.dirty=true;wake();
}
function resize(){
  instances.forEach(item=>{item.width=item.root.offsetWidth;item.height=item.root.offsetHeight;const scale=Math.min(devicePixelRatio||1,1.5);item.canvas.width=Math.max(1,Math.round(item.width*scale));item.canvas.height=Math.max(1,Math.round(item.height*scale));item.dirty=true;});force=true;wake();
}
function frame(now){
  raf=0;if(!visible||document.hidden)return;
  const dt=Math.min(.05,(now-last)/1000||1/60);last=now;let busy=false;
  instances.forEach(item=>{
    const {x,y,light,root}=item;
    if(!item.introDone&&carousel?.getState().introDone){
      if(item.introStart===null)item.introStart=now+item.index*65;
      const p=clamp((now-item.introStart)/INTRO_MS,0,1),sweep=Math.pow(1-p,3);x.p=.62*sweep;y.p=-.55*sweep;light.p=sweep;item.pointer.x=.62*item.width*.5;item.pointer.y=-.55*item.height*.5;if(p>=1)item.introDone=true;item.dirty=true;
    }else{
      const steps=Math.max(1,Math.ceil(dt*120)),h=dt/steps;
      for(let n=0;n<steps;n++)for(const axis of [x,y]){axis.v+=(120*(axis.target-axis.p)-16*axis.v)*h;axis.p+=axis.v*h;}
      light.p+=(light.target-light.p)*(1-Math.exp(-dt/(light.target>light.p?.18:.45)));
    }
    const moving=Math.abs(x.target-x.p)>.0005||Math.abs(y.target-y.p)>.0005||Math.abs(x.v)>.0005||Math.abs(y.v)>.0005||Math.abs(light.target-light.p)>.002;
    if(!moving&&item.introDone){x.p=x.target;y.p=y.target;x.v=0;y.v=0;light.p=light.target;}
    const strength=media.matches?0:8;
    root.style.setProperty('--rf-rotate-x',`${(y.p*strength).toFixed(3)}deg`);root.style.setProperty('--rf-rotate-y',`${(-x.p*strength).toFixed(3)}deg`);root.style.setProperty('--rf-light',light.p.toFixed(4));
    if(renderer&&(force||item.dirty||moving||!item.introDone)){
      if(surface.width!==item.canvas.width||surface.height!==item.canvas.height){surface.width=item.canvas.width;surface.height=item.canvas.height;}
      renderer.renderPlate({width:item.width,height:item.height,rotateX:y.p*strength*Math.PI/180,rotateY:-x.p*strength*Math.PI/180,capture:0,glare:media.matches?0:.42*light.p,pointerX:item.pointer.x,pointerY:item.pointer.y,settings});
      item.context.clearRect(0,0,item.canvas.width,item.canvas.height);item.context.drawImage(surface,0,0);
    }
    item.dirty=false;busy=busy||moving||!item.introDone;
  });
  force=false;if(busy&&!media.matches)wake();
}
function wake(){if(!raf&&visible&&!document.hidden){last=performance.now();raf=requestAnimationFrame(frame);}}
instances.forEach(item=>{
  scope.listen(item.root,'pointerenter',e=>{if(e.pointerType==='mouse')aim(item,e);});
  scope.listen(item.root,'pointermove',e=>{if(e.pointerType==='mouse'||e.pointerId===item.touchId)aim(item,e);});
  scope.listen(item.root,'pointerleave',e=>{if(e.pointerType==='mouse')reset(item);});
  scope.listen(item.root,'pointerdown',e=>{if(e.pointerType!=='mouse'){item.touchId=e.pointerId;aim(item,e);}});
  const up=e=>{if(e.pointerId===item.touchId){item.touchId=null;reset(item);}};scope.listen(item.root,'pointerup',up);scope.listen(item.root,'pointercancel',up);
});
const resizeObserver=scope.resize(resize);instances.forEach(item=>resizeObserver.observe(item.root));
const observer=scope.intersection(([entry])=>{visible=entry.isIntersecting;if(!visible){cancelAnimationFrame(raf);raf=0;}else wake();});observer.observe(root);
scope.listen(media,'change',()=>{instances.forEach(item=>{item.introDone=true;for(const axis of [item.x,item.y]){axis.p=0;axis.v=0;axis.target=0;}item.light.p=0;item.light.target=0;item.dirty=true;});force=true;wake();});
scope.listen(document,'visibilitychange',()=>{if(document.hidden){cancelAnimationFrame(raf);raf=0;}else wake();});
scope.listen(surface,'webglcontextlost',e=>{e.preventDefault();renderer=null;instances.forEach(item=>item.root.dataset.reflectiveRenderer='fallback');});
scope.onDispose(e=>{if(!e.persisted){cancelAnimationFrame(raf);resizeObserver.disconnect();observer.disconnect();renderer?.dispose();}});
scope.reflections={contextCount:renderer?1:0,getStates:()=>instances.map(item=>({renderer:renderer?'webgl':'fallback',x:item.x.p,y:item.y.p,light:item.light.p,introDone:item.introDone,reduced:media.matches,width:item.width,height:item.height})),settings:{...settings,tiltStrength:8,glare:.42}};
scope.onDispose(() => instances.forEach(item => { item.canvas.remove(); item.root.querySelector('.reflective-feature__rim')?.remove(); }));
resize();

}