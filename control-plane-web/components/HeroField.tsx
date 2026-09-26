"use client";

import { useEffect, useRef } from "react";
import { cn } from "@/lib/utils";

/*
 * The landing page's hero dot field (landing/script.js, FIELD_VERTEX), ported
 * to plain WebGL so the console doesn't need Three.js: a drifting halftone
 * grid that clears towards the centre, a gravity well that pulls in and lights
 * the dots under the cursor, and a shockwave on click.
 */

const VERTEX = /* glsl */ `
  attribute vec2 aPosition;
  uniform float uTime;
  uniform vec2 uRes;
  uniform vec2 uMouse;
  uniform float uPull;
  uniform vec3 uClick;
  uniform float uPR;
  varying float vLight;
  varying float vAlpha;

  // 2D simplex noise (Ashima Arts, MIT).
  vec3 permute(vec3 x) { return mod(((x * 34.0) + 1.0) * x, 289.0); }
  float snoise(vec2 v) {
    const vec4 C = vec4(0.211324865405187, 0.366025403784439, -0.577350269189626, 0.024390243902439);
    vec2 i = floor(v + dot(v, C.yy));
    vec2 x0 = v - i + dot(i, C.xx);
    vec2 i1 = (x0.x > x0.y) ? vec2(1.0, 0.0) : vec2(0.0, 1.0);
    vec4 x12 = x0.xyxy + C.xxzz;
    x12.xy -= i1;
    i = mod(i, 289.0);
    vec3 p = permute(permute(i.y + vec3(0.0, i1.y, 1.0)) + i.x + vec3(0.0, i1.x, 1.0));
    vec3 m = max(0.5 - vec3(dot(x0, x0), dot(x12.xy, x12.xy), dot(x12.zw, x12.zw)), 0.0);
    m = m * m; m = m * m;
    vec3 x = 2.0 * fract(p * C.www) - 1.0;
    vec3 h = abs(x) - 0.5;
    vec3 a0 = x - floor(x + 0.5);
    m *= 1.79284291400159 - 0.85373472095314 * (a0 * a0 + h * h);
    vec3 g;
    g.x = a0.x * x0.x + h.x * x0.y;
    g.yz = a0.yz * x12.xz + h.yz * x12.yw;
    return 130.0 * dot(m, g);
  }

  void main() {
    vec2 p = aPosition;

    // Halftone mask: dense towards the edges, clear behind the centred card.
    vec2 c = (p / uRes - 0.5) * vec2(uRes.x / uRes.y, 1.0);
    float edge = smoothstep(0.22, 0.75, length(c));
    float n = snoise(p * 0.0045 + vec2(uTime * 0.04, -uTime * 0.03)) * 0.5 + 0.5;
    float base = edge * smoothstep(0.25, 0.85, n);

    // Gravity well around the cursor: dots are pulled in and lit up...
    vec2 d = uMouse - p;
    float dist = length(d);
    float well = exp(-(dist * dist) / (180.0 * 180.0)) * uPull;
    p += d * 0.32 * well;
    // ...and swallowed at the centre.
    float horizon = mix(1.0, smoothstep(6.0, 34.0, dist), uPull);

    // Click shockwave.
    float age = uTime - uClick.z;
    vec2 dc = p - uClick.xy;
    float dcl = length(dc);
    float wave = exp(-pow((dcl - age * 520.0) / 36.0, 2.0)) * exp(-age * 1.6) * step(0.0, age);
    p += (dcl > 0.0 ? dc / dcl : vec2(0.0)) * wave * 16.0;

    vLight = clamp(well * 1.2 + wave, 0.0, 1.0);
    vAlpha = clamp(base * 0.55 + vLight * 0.85, 0.0, 1.0) * horizon;
    gl_PointSize = (1.3 + 1.7 * max(base, vLight)) * uPR;
    gl_Position = vec4(p.x / uRes.x * 2.0 - 1.0, 1.0 - p.y / uRes.y * 2.0, 0.0, 1.0);
  }
`;

const FRAGMENT = /* glsl */ `
  precision mediump float;
  varying float vLight;
  varying float vAlpha;
  void main() {
    float d = length(gl_PointCoord - 0.5);
    float a = smoothstep(0.5, 0.2, d) * vAlpha;
    vec3 color = mix(vec3(0.55, 0.64, 1.0), vec3(0.92, 0.95, 1.0), vLight);
    gl_FragColor = vec4(color * a, a); // premultiplied: the canvas is transparent
  }
`;

function compile(gl: WebGLRenderingContext, type: number, source: string) {
  const shader = gl.createShader(type)!;
  gl.shaderSource(shader, source);
  gl.compileShader(shader);
  return shader;
}

interface HeroFieldProps {
  className?: string;
  /** Clicks on elements matching this selector don't trigger a shockwave. */
  ignoreClicksOn?: string;
}

export function HeroField({ className, ignoreClicksOn = "a, button, input, label, [data-no-wave]" }: HeroFieldProps) {
  const canvasRef = useRef<HTMLCanvasElement>(null);

  useEffect(() => {
    const canvas = canvasRef.current;
    const gl = canvas?.getContext("webgl", { alpha: true, antialias: false, premultipliedAlpha: true });
    if (!canvas || !gl) return; // No WebGL: the glow behind still carries the page.

    const program = gl.createProgram()!;
    gl.attachShader(program, compile(gl, gl.VERTEX_SHADER, VERTEX));
    gl.attachShader(program, compile(gl, gl.FRAGMENT_SHADER, FRAGMENT));
    gl.linkProgram(program);
    if (!gl.getProgramParameter(program, gl.LINK_STATUS)) return;
    gl.useProgram(program);
    gl.enable(gl.BLEND);
    gl.blendFunc(gl.ONE, gl.ONE_MINUS_SRC_ALPHA);

    const u = (name: string) => gl.getUniformLocation(program, name);
    const uTime = u("uTime");
    const uRes = u("uRes");
    const uMouse = u("uMouse");
    const uPull = u("uPull");
    const uClick = u("uClick");
    const uPR = u("uPR");
    const buffer = gl.createBuffer();
    const aPosition = gl.getAttribLocation(program, "aPosition");

    const reduceMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    const pr = Math.min(window.devicePixelRatio || 1, 2);
    const state = { width: 1, height: 1, count: 0, time: reduceMotion ? 12 : 0 };
    const mouse = { x: -9999, y: -9999, tx: -9999, ty: -9999, pull: 0, tpull: 0 };
    const click = { x: -9999, y: -9999, t: -99 };

    const draw = () => {
      gl.viewport(0, 0, canvas.width, canvas.height);
      gl.clearColor(0, 0, 0, 0);
      gl.clear(gl.COLOR_BUFFER_BIT);
      gl.uniform1f(uTime, state.time);
      gl.uniform2f(uRes, state.width, state.height);
      gl.uniform2f(uMouse, mouse.x, mouse.y);
      gl.uniform1f(uPull, mouse.pull);
      gl.uniform3f(uClick, click.x, click.y, click.t);
      gl.uniform1f(uPR, pr);
      gl.drawArrays(gl.POINTS, 0, state.count);
    };

    const build = () => {
      const width = canvas.clientWidth;
      const height = canvas.clientHeight;
      if (!width || !height) return;
      canvas.width = Math.round(width * pr);
      canvas.height = Math.round(height * pr);
      Object.assign(state, { width, height });

      const gap = width < 640 ? 15 : 17;
      const cols = Math.ceil(width / gap) + 1;
      const rows = Math.ceil(height / gap) + 1;
      const positions = new Float32Array(cols * rows * 2);
      let i = 0;
      for (let y = 0; y < rows; y++) {
        for (let x = 0; x < cols; x++) {
          positions[i++] = x * gap + (width % gap) / 2;
          positions[i++] = y * gap + (height % gap) / 2;
        }
      }
      state.count = cols * rows;
      gl.bindBuffer(gl.ARRAY_BUFFER, buffer);
      gl.bufferData(gl.ARRAY_BUFFER, positions, gl.STATIC_DRAW);
      gl.enableVertexAttribArray(aPosition);
      gl.vertexAttribPointer(aPosition, 2, gl.FLOAT, false, 0, 0);
      draw();
    };

    const resizeObserver = new ResizeObserver(build);
    resizeObserver.observe(canvas);
    build();
    const reveal = requestAnimationFrame(() => canvas.dataset.ready = "true");

    if (reduceMotion) {
      return () => {
        cancelAnimationFrame(reveal);
        resizeObserver.disconnect();
      };
    }

    const local = (event: PointerEvent) => {
      const rect = canvas.getBoundingClientRect();
      return { x: event.clientX - rect.left, y: event.clientY - rect.top };
    };
    const onMove = (event: PointerEvent) => {
      const { x, y } = local(event);
      if (mouse.tpull === 0) Object.assign(mouse, { x, y }); // no swoop-in from far away
      Object.assign(mouse, { tx: x, ty: y, tpull: event.pointerType === "touch" ? 0 : 1 });
    };
    const onLeave = () => (mouse.tpull = 0);
    const onDown = (event: PointerEvent) => {
      if ((event.target as Element | null)?.closest?.(ignoreClicksOn)) return;
      const { x, y } = local(event);
      Object.assign(click, { x, y, t: state.time });
    };
    window.addEventListener("pointermove", onMove, { passive: true });
    window.addEventListener("pointerdown", onDown, { passive: true });
    document.documentElement.addEventListener("pointerleave", onLeave);

    let raf = 0;
    let last = performance.now();
    const frame = (now: number) => {
      const dt = Math.min((now - last) / 1000, 0.05);
      last = now;
      state.time += dt;
      mouse.x += (mouse.tx - mouse.x) * 0.14;
      mouse.y += (mouse.ty - mouse.y) * 0.14;
      mouse.pull += (mouse.tpull - mouse.pull) * 0.06;
      draw();
      raf = requestAnimationFrame(frame);
    };
    // Pause while the tab is hidden.
    const sync = () => {
      cancelAnimationFrame(raf);
      if (!document.hidden) {
        last = performance.now();
        raf = requestAnimationFrame(frame);
      }
    };
    document.addEventListener("visibilitychange", sync);
    sync();

    return () => {
      cancelAnimationFrame(reveal);
      cancelAnimationFrame(raf);
      resizeObserver.disconnect();
      window.removeEventListener("pointermove", onMove);
      window.removeEventListener("pointerdown", onDown);
      document.documentElement.removeEventListener("pointerleave", onLeave);
      document.removeEventListener("visibilitychange", sync);
    };
  }, [ignoreClicksOn]);

  return (
    <canvas
      ref={canvasRef}
      aria-hidden="true"
      className={cn(
        "pointer-events-none absolute inset-0 size-full opacity-0 transition-opacity duration-[1400ms] ease-out-expo data-[ready=true]:opacity-100",
        className
      )}
    />
  );
}
