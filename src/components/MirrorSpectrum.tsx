import { useEffect, useMemo, useRef } from 'react';
import type { LocalWebAudioEngine } from '../core/localAudioEngine';

interface MirrorSpectrumProps {
  audioEngine: LocalWebAudioEngine;
  isPlaying: boolean;
  isVoiceActive: boolean;
  progress?: number;
  trackSeed?: number;
  onSeekFraction?: (fraction: number) => void;
  audioEnergyRef: React.MutableRefObject<number>;
}

const BAR_COUNT = 64;

export function MirrorSpectrum({
  audioEngine,
  isPlaying,
  isVoiceActive,
  progress = 0,
  trackSeed = 1,
  onSeekFraction,
  audioEnergyRef,
}: MirrorSpectrumProps) {
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const progressRef = useRef(progress);
  progressRef.current = progress;

  const profile = useMemo(() => {
    const arr = new Float32Array(BAR_COUNT);
    for (let i = 0; i < BAR_COUNT; i++) {
      const t = i / (BAR_COUNT - 1);
      const env = 0.25 + 0.75 * Math.sin(t * Math.PI);
      const h1 = Math.abs(Math.sin((i + trackSeed * 0.37) * 0.62));
      const h2 = Math.abs(Math.sin((i * 1.41 + trackSeed * 0.91) * 0.31));
      const spike = (i + trackSeed) % 11 === 0 ? 0.32 : 0;
      arr[i] = Math.min(1, Math.max(0.1, 0.12 + (h1 * 0.55 + h2 * 0.35) * env + spike));
    }
    return arr;
  }, [trackSeed]);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;

    let rafId = 0;
    let t = 0;

    const render = () => {
      t += isPlaying || isVoiceActive ? 0.08 : 0.015;
      const dpr = window.devicePixelRatio || 1;
      const rect = canvas.getBoundingClientRect();
      const w = Math.max(1, Math.round(rect.width * dpr));
      const h = Math.max(1, Math.round(rect.height * dpr));
      if (canvas.width !== w || canvas.height !== h) {
        canvas.width = w;
        canvas.height = h;
      }

      ctx.clearRect(0, 0, w, h);
      const { bars, energy } = audioEngine.getSpectrum(BAR_COUNT);
      const liveEnergy = isVoiceActive ? Math.max(0.55, energy) : energy;
      audioEnergyRef.current = isPlaying || isVoiceActive ? Math.max(0.18, liveEnergy) : 0.06;

      const gap = Math.max(2 * dpr, w * 0.008);
      const barW = Math.max(1.8 * dpr, (w - gap * (BAR_COUNT - 1)) / BAR_COUNT);
      const midY = h * 0.5;
      const playedGrad = ctx.createLinearGradient(0, 0, w, 0);
      playedGrad.addColorStop(0, '#8b5cf6');
      playedGrad.addColorStop(0.55, '#a78bfa');
      playedGrad.addColorStop(1, '#4fd1ff');

      const prog = Math.min(1, Math.max(0, progressRef.current));

      for (let i = 0; i < BAR_COUNT; i++) {
        const frac = i / (BAR_COUNT - 1);
        const base = profile[i];
        const live = isPlaying || isVoiceActive
          ? Math.min(1, Math.max(0.1, base * (0.65 + (bars[i] ?? 0.2) * 0.75) * (0.85 + 0.18 * Math.sin(t + i * 0.35))))
          : base * 0.72;

        const barH = Math.max(2.5 * dpr, live * h * 0.92);
        const x = i * (barW + gap);
        const y = midY - barH * 0.5;

        ctx.fillStyle = frac <= prog ? playedGrad : '#282d46';
        ctx.beginPath();
        ctx.roundRect(x, y, barW, barH, barW * 0.5);
        ctx.fill();
      }

      rafId = requestAnimationFrame(render);
    };

    rafId = requestAnimationFrame(render);
    return () => cancelAnimationFrame(rafId);
  }, [audioEngine, isPlaying, isVoiceActive, audioEnergyRef, profile]);

  const handleClick = (e: React.MouseEvent<HTMLCanvasElement>) => {
    if (!onSeekFraction) return;
    const rect = e.currentTarget.getBoundingClientRect();
    if (rect.width <= 0) return;
    const frac = Math.min(1, Math.max(0, (e.clientX - rect.left) / rect.width));
    onSeekFraction(frac);
  };

  return (
    <canvas
      ref={canvasRef}
      onClick={handleClick}
      className="h-10 w-full block cursor-pointer"
    />
  );
}
