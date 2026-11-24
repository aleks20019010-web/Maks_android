import { useEffect, useRef } from 'react';

export type AvatarMode = 'idle' | 'listening' | 'speaking' | 'music' | 'happy' | 'curious' | 'error';

interface AvatarPalette {
  primary: string;
  secondary: string;
  accent: string;
  core: string;
}

const STATE_PALETTES: Record<AvatarMode, AvatarPalette> = {
  idle: { primary: '#38bdf8', secondary: '#818cf8', accent: '#22d3ee', core: '#e0f2fe' },
  listening: { primary: '#00e5ff', secondary: '#38bdf8', accent: '#2dd4bf', core: '#ffffff' },
  speaking: { primary: '#38bdf8', secondary: '#c084fc', accent: '#22d3ee', core: '#ffffff' },
  music: { primary: '#22d3ee', secondary: '#a855f7', accent: '#2dd4bf', core: '#f0fdfa' },
  happy: { primary: '#2dd4bf', secondary: '#6366f1', accent: '#38bdf8', core: '#f0fdfa' },
  curious: { primary: '#22d3ee', secondary: '#f59e0b', accent: '#38bdf8', core: '#fef3c7' },
  error: { primary: '#f59e0b', secondary: '#ef4444', accent: '#fbbf24', core: '#fef3c7' },
};

const SPEED_MUL: Record<AvatarMode, number> = {
  idle: 0.72,
  listening: 1.3,
  speaking: 1.55,
  music: 1.25,
  happy: 1.18,
  curious: 1.45,
  error: 0.85,
};

interface PlasmaAvatarProps {
  mode: AvatarMode;
  audioLevelRef: React.MutableRefObject<number>;
  onClick?: () => void;
}

function hexToRgba(hex: string, alpha: number): string {
  const clean = hex.replace('#', '');
  const r = parseInt(clean.slice(0, 2), 16);
  const g = parseInt(clean.slice(2, 4), 16);
  const b = parseInt(clean.slice(4, 6), 16);
  return `rgba(${r}, ${g}, ${b}, ${alpha.toFixed(3)})`;
}

export function PlasmaAvatar({ mode, audioLevelRef, onClick }: PlasmaAvatarProps) {
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const modeRef = useRef(mode);
  modeRef.current = mode;

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;

    const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    let rafId = 0;
    let phase = 0;
    let smoothAudio = 0;
    let lastTs = performance.now();

    const draw = (ts: number) => {
      const dt = Math.min(0.05, (ts - lastTs) / 1000);
      lastTs = ts;
      const m = modeRef.current;
      phase += reducedMotion ? dt * 0.14 : dt * SPEED_MUL[m];

      const targetEnergy = Math.min(1, Math.max(0, audioLevelRef.current));
      smoothAudio += (targetEnergy - smoothAudio) * Math.min(1, dt * 10);
      const energy = smoothAudio;

      const dpr = window.devicePixelRatio || 1;
      const rect = canvas.getBoundingClientRect();
      const w = Math.max(1, Math.round(rect.width * dpr));
      const h = Math.max(1, Math.round(rect.height * dpr));
      if (canvas.width !== w || canvas.height !== h) {
        canvas.width = w;
        canvas.height = h;
      }

      ctx.clearRect(0, 0, w, h);
      const minDim = Math.min(w, h);
      if (minDim <= 4) {
        rafId = requestAnimationFrame(draw);
        return;
      }

      const cx = w * 0.5;
      const cy = h * 0.5;
      const maxSafeR = minDim * 0.49;
      const breathe = Math.sin(phase * 1.9) * 0.025;
      const sphereR = minDim * 0.315 * (1 + energy * 0.09 + breathe);
      const haloR = Math.min(sphereR * 1.48, maxSafeR);
      const pal = STATE_PALETTES[m];

      // 1. Ambient nebula halo
      const halo = ctx.createRadialGradient(cx, cy, 0, cx, cy, haloR);
      halo.addColorStop(0, hexToRgba(pal.primary, 0.3 + energy * 0.16));
      halo.addColorStop(0.48, hexToRgba(pal.secondary, 0.16 + energy * 0.08));
      halo.addColorStop(0.78, hexToRgba(pal.accent, 0.05));
      halo.addColorStop(1, 'rgba(0,0,0,0)');
      ctx.fillStyle = halo;
      ctx.beginPath();
      ctx.arc(cx, cy, haloR, 0, Math.PI * 2);
      ctx.fill();

      // 2. Volumetric dark glass sphere + Fresnel rim
      const bodyR = sphereR * 1.04;
      const body = ctx.createRadialGradient(cx, cy, 0, cx, cy, bodyR);
      body.addColorStop(0, 'rgba(10, 18, 40, 0.88)');
      body.addColorStop(0.68, 'rgba(6, 11, 27, 0.82)');
      body.addColorStop(0.88, hexToRgba(pal.primary, 0.24 + energy * 0.12));
      body.addColorStop(0.97, hexToRgba(pal.accent, 0.38 + energy * 0.18));
      body.addColorStop(1, 'rgba(0,0,0,0)');
      ctx.fillStyle = body;
      ctx.beginPath();
      ctx.arc(cx, cy, bodyR, 0, Math.PI * 2);
      ctx.fill();

      // 3. Precision orbital HUD ring + glowing node
      const ringR = Math.min(sphereR * 1.18, maxSafeR * 0.96);
      ctx.save();
      ctx.strokeStyle = hexToRgba(pal.primary, 0.14);
      ctx.lineWidth = Math.max(1, minDim * 0.0045);
      ctx.beginPath();
      ctx.arc(cx, cy, ringR, 0, Math.PI * 2);
      ctx.stroke();

      ctx.globalCompositeOperation = 'screen';
      const arcStart1 = phase * 0.45;
      const arcSweep1 = (72 * Math.PI) / 180;
      ctx.strokeStyle = hexToRgba(pal.accent, 0.42 + energy * 0.25);
      ctx.lineWidth = Math.max(1.4, minDim * 0.009);
      ctx.lineCap = 'round';
      ctx.beginPath();
      ctx.arc(cx, cy, ringR, arcStart1, arcStart1 + arcSweep1);
      ctx.stroke();

      const arcStart2 = -phase * 0.33 + 2.35;
      ctx.strokeStyle = hexToRgba(pal.secondary, 0.36 + energy * 0.22);
      ctx.lineWidth = Math.max(1.2, minDim * 0.007);
      ctx.beginPath();
      ctx.arc(cx, cy, ringR, arcStart2, arcStart2 + (54 * Math.PI) / 180);
      ctx.stroke();

      const nodeAngle = arcStart1 + arcSweep1;
      const nx = cx + Math.cos(nodeAngle) * ringR;
      const ny = cy + Math.sin(nodeAngle) * ringR;
      const nodeGlowR = Math.max(3, minDim * 0.032);
      const nodeGrad = ctx.createRadialGradient(nx, ny, 0, nx, ny, nodeGlowR);
      nodeGrad.addColorStop(0, hexToRgba(pal.core, 0.95));
      nodeGrad.addColorStop(0.5, hexToRgba(pal.accent, 0.45));
      nodeGrad.addColorStop(1, 'rgba(0,0,0,0)');
      ctx.fillStyle = nodeGrad;
      ctx.beginPath();
      ctx.arc(nx, ny, nodeGlowR, 0, Math.PI * 2);
      ctx.fill();

      // 4. Audio-reactive harmonic corona
      const coronaSteps = 72;
      ctx.beginPath();
      for (let s = 0; s <= coronaSteps; s++) {
        const a = (s / coronaSteps) * Math.PI * 2;
        const w1 = Math.sin(a * 5 + phase * 3.4) * (0.025 + energy * 0.075);
        const w2 = Math.cos(a * 3 - phase * 2.2) * (0.018 + energy * 0.045);
        const rr = Math.min(sphereR * (0.96 + w1 + w2), maxSafeR * 0.94);
        const px = cx + Math.cos(a) * rr;
        const py = cy + Math.sin(a) * rr;
        if (s === 0) ctx.moveTo(px, py);
        else ctx.lineTo(px, py);
      }
      ctx.lineJoin = 'round';
      ctx.strokeStyle = hexToRgba(pal.accent, 0.16 + energy * 0.22);
      ctx.lineWidth = Math.max(2.4, minDim * 0.02);
      ctx.stroke();
      ctx.strokeStyle = hexToRgba(pal.core, 0.28 + energy * 0.34);
      ctx.lineWidth = Math.max(1, minDim * 0.006);
      ctx.stroke();

      // 5. 8 high-resolution 3D dual-pass bloom plasma silk ribbons
      const ribbonCount = 8;
      const steps = 72;
      const bloomW = Math.max(3, minDim * (0.024 + energy * 0.012));
      const coreW = Math.max(1.3, minDim * (0.0085 + energy * 0.0045));

      for (let i = 0; i < ribbonCount; i++) {
        const tiltX = (i / ribbonCount) * Math.PI + Math.sin(phase * 0.45 + i) * 0.18;
        const tiltZ = i * 0.62 + phase * (0.28 + (i % 3) * 0.11);
        const spin = phase * (0.85 + (i % 4) * 0.22) + i * 1.13;
        const ribbonScale = 0.72 + (i % 3) * 0.11;

        const cosTX = Math.cos(tiltX);
        const sinTX = Math.sin(tiltX);
        const cosSp = Math.cos(spin);
        const sinSp = Math.sin(spin);
        const cosTZ = Math.cos(tiltZ);
        const sinTZ = Math.sin(tiltZ);

        ctx.beginPath();
        for (let s = 0; s <= steps; s++) {
          const a = (s / steps) * Math.PI * 2;
          const harmonic =
            1 +
            0.075 * Math.sin(a * 3 + spin * 1.4 + i) +
            (0.035 + energy * 0.11) * Math.cos(a * 4 - phase * 3.1);
          const rad = ribbonScale * harmonic;

          const x0 = Math.cos(a) * rad;
          const y0 = Math.sin(a) * cosTX * rad;
          const z0 = Math.sin(a) * sinTX * rad;

          const x1 = x0 * cosSp - z0 * sinSp;
          const z1 = x0 * sinSp + z0 * cosSp;

          const x2 = x1 * cosTZ - y0 * sinTZ;
          const y2 = x1 * sinTZ + y0 * cosTZ;

          const persp = 2.9 / (2.9 - z1 * 0.62);
          const px = cx + x2 * sphereR * persp;
          const py = cy + y2 * sphereR * persp;
          if (s === 0) ctx.moveTo(px, py);
          else ctx.lineTo(px, py);
        }

        const rHex = i % 3 === 0 ? pal.primary : i % 3 === 1 ? pal.secondary : pal.accent;
        ctx.strokeStyle = hexToRgba(rHex, 0.16 + energy * 0.1);
        ctx.lineWidth = bloomW;
        ctx.stroke();

        ctx.strokeStyle = hexToRgba(rHex, 0.56 + (i % 2) * 0.18);
        ctx.lineWidth = coreW;
        ctx.stroke();
      }

      // 6. 18 orbiting 3D micro-particles
      for (let p = 0; p < 18; p++) {
        const seed = p * 2.39996;
        const theta = phase * (0.45 + (p % 5) * 0.14) + seed;
        const phi = Math.sin(phase * 0.35 + p * 0.9) * 1.15 + ((p % 3) - 1) * 0.45;
        const dist = sphereR * (0.68 + (p % 4) * 0.13 + energy * 0.08);

        const x3 = Math.cos(theta) * Math.cos(phi);
        const y3 = Math.sin(phi);
        const z3 = Math.sin(theta) * Math.cos(phi);
        const depth = (z3 + 1.2) / 2.2;
        const persp = 2.6 / (2.6 - z3 * 0.55);

        const px = cx + x3 * dist * persp;
        const py = cy + y3 * dist * persp;
        const twinkle = 0.45 + 0.55 * Math.sin(phase * 3.8 + p * 1.7);
        const dotR = Math.max(1.1, (minDim * 0.008 + (p % 3) * minDim * 0.003) * (0.6 + depth * 0.7));
        const pHex = p % 2 === 0 ? pal.core : pal.accent;

        ctx.fillStyle = hexToRgba(pHex, (0.28 + depth * 0.52) * twinkle);
        ctx.beginPath();
        ctx.arc(px, py, dotR, 0, Math.PI * 2);
        ctx.fill();
      }

      // 7. Volumetric white-hot energy core
      const coreR = sphereR * (0.68 + energy * 0.14);
      const core = ctx.createRadialGradient(cx, cy, 0, cx, cy, coreR);
      core.addColorStop(0, 'rgba(255,255,255,0.96)');
      core.addColorStop(0.18, hexToRgba(pal.core, 0.88));
      core.addColorStop(0.45, hexToRgba(pal.primary, 0.48 + energy * 0.16));
      core.addColorStop(0.76, hexToRgba(pal.secondary, 0.18));
      core.addColorStop(1, 'rgba(0,0,0,0)');
      ctx.fillStyle = core;
      ctx.beginPath();
      ctx.arc(cx, cy, coreR, 0, Math.PI * 2);
      ctx.fill();

      // 8. Glass specular highlight arc
      const hlR = sphereR * 0.86;
      const hlGrad = ctx.createLinearGradient(cx - hlR, cy - hlR, cx, cy);
      hlGrad.addColorStop(0, 'rgba(255,255,255,0.34)');
      hlGrad.addColorStop(0.5, hexToRgba(pal.core, 0.1));
      hlGrad.addColorStop(1, 'rgba(0,0,0,0)');
      ctx.strokeStyle = hlGrad;
      ctx.lineWidth = Math.max(1.5, minDim * 0.011);
      ctx.beginPath();
      ctx.arc(cx, cy, hlR, (205 * Math.PI) / 180, (263 * Math.PI) / 180);
      ctx.stroke();

      ctx.restore();
      rafId = requestAnimationFrame(draw);
    };

    rafId = requestAnimationFrame(draw);
    return () => cancelAnimationFrame(rafId);
  }, [audioLevelRef]);

  return (
    <button
      type="button"
      onClick={onClick}
      aria-label="Голосовая активация Макса"
      className="relative h-44 w-44 sm:h-48 sm:w-48 shrink-0 rounded-full focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-sky-400 transition-transform duration-200 hover:scale-[1.03] active:scale-[0.98] cursor-pointer"
    >
      <canvas ref={canvasRef} className="h-full w-full block" />
    </button>
  );
}
