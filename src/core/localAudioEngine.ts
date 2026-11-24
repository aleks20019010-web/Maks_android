import type { LocalTrackItem } from './maksMusicEngine';

export interface EqPresetDef {
  id: string;
  label: string;
  gainsDb: [number, number, number, number, number];
}

export const EQ_PRESETS: EqPresetDef[] = [
  { id: 'flat', label: 'Ровный', gainsDb: [0, 0, 0, 0, 0] },
  { id: 'deep', label: 'Глубокий', gainsDb: [5, 2.5, -0.5, 1, 2] },
  { id: 'vocal', label: 'Вокал', gainsDb: [-1.5, 1, 4, 3, 1] },
  { id: 'electronic', label: 'Электро', gainsDb: [4.5, 2, -1, 2.5, 4.5] },
  { id: 'rock', label: 'Рок', gainsDb: [3.5, 1.5, -1.5, 2.5, 4] },
  { id: 'classic', label: 'Классика', gainsDb: [1.5, 0.5, 0, 1.5, 3] },
  { id: 'club', label: 'Клуб', gainsDb: [4.5, 3, 1.5, 2, 1.5] },
];

const EQ_FREQS = [60, 250, 1000, 4000, 14000];
export const EQ_BAND_LABELS = ['60 Hz', '250 Hz', '1 kHz', '4 kHz', '14 kHz'] as const;

/**
 * Локальный аудиодвижок EQ Studio для веб-превью:
 *   - 5-полосный параметрический эквалайзер (BiquadFilterNode) + мастер-байпас
 *   - Bass Boost lowshelf-фильтр
 *   - Стереобаланс L/R (StereoPannerNode)
 *   - Плавный ducking (100 мс вниз / 1000 мс вверх) + AnalyserNode.
 */
export class LocalWebAudioEngine {
  private ctx: AudioContext | null = null;
  private masterGain: GainNode | null = null;
  private duckGain: GainNode | null = null;
  private panner: StereoPannerNode | null = null;
  private bassBoostFilter: BiquadFilterNode | null = null;
  private filters: BiquadFilterNode[] = [];
  private analyser: AnalyserNode | null = null;
  private freqData: Uint8Array<ArrayBuffer> = new Uint8Array(new ArrayBuffer(64));

  private audioEl: HTMLAudioElement | null = null;
  private mediaSource: MediaElementAudioSourceNode | null = null;

  private synthTimer: number | null = null;
  private synthStep = 0;
  private currentTrack: LocalTrackItem | null = null;
  private isPlaying = false;
  private userVolume = 0.72;
  private eqEnabled = true;
  private storedBandsDb: number[] = [0, 0, 0, 0, 0];
  private bassBoostEnabled = false;
  private balance = 0;

  private ensureContext(): AudioContext {
    if (this.ctx) {
      if (this.ctx.state === 'suspended') {
        void this.ctx.resume();
      }
      return this.ctx;
    }

    const AudioCtx = window.AudioContext || (window as unknown as { webkitAudioContext: typeof AudioContext }).webkitAudioContext;
    const ctx = new AudioCtx();
    const duckGain = ctx.createGain();
    duckGain.gain.value = 1;

    const masterGain = ctx.createGain();
    masterGain.gain.value = this.userVolume;

    const panner = ctx.createStereoPanner();
    panner.pan.value = this.balance;

    const bassBoostFilter = ctx.createBiquadFilter();
    bassBoostFilter.type = 'lowshelf';
    bassBoostFilter.frequency.value = 85;
    bassBoostFilter.gain.value = this.bassBoostEnabled ? 6.5 : 0;

    const analyser = ctx.createAnalyser();
    analyser.fftSize = 128;
    analyser.smoothingTimeConstant = 0.78;
    this.freqData = new Uint8Array(new ArrayBuffer(analyser.frequencyBinCount));

    const filters = EQ_FREQS.map((freq, idx) => {
      const f = ctx.createBiquadFilter();
      f.type = idx === 0 ? 'lowshelf' : idx === EQ_FREQS.length - 1 ? 'highshelf' : 'peaking';
      f.frequency.value = freq;
      f.Q.value = 1.1;
      f.gain.value = this.eqEnabled ? (this.storedBandsDb[idx] ?? 0) : 0;
      return f;
    });

    // input → filters[0..4] → bassBoost → panner → duckGain → masterGain → analyser → destination
    for (let i = 0; i < filters.length - 1; i++) {
      filters[i].connect(filters[i + 1]);
    }
    filters[filters.length - 1].connect(bassBoostFilter);
    bassBoostFilter.connect(panner);
    panner.connect(duckGain);
    duckGain.connect(masterGain);
    masterGain.connect(analyser);
    analyser.connect(ctx.destination);

    this.ctx = ctx;
    this.filters = filters;
    this.bassBoostFilter = bassBoostFilter;
    this.panner = panner;
    this.duckGain = duckGain;
    this.masterGain = masterGain;
    this.analyser = analyser;

    return ctx;
  }

  public playTrack(track: LocalTrackItem, onEnded: () => void): void {
    this.stopInternal();
    const ctx = this.ensureContext();
    this.currentTrack = track;
    this.isPlaying = true;

    if (track.objectUrl) {
      if (!this.audioEl) {
        this.audioEl = new Audio();
        this.audioEl.crossOrigin = 'anonymous';
        this.mediaSource = ctx.createMediaElementSource(this.audioEl);
        this.mediaSource.connect(this.filters[0]);
      }
      this.audioEl.src = track.objectUrl;
      this.audioEl.onended = onEnded;
      void this.audioEl.play().catch(() => {});
    } else {
      this.startDemoSynth(track);
    }
  }

  public pause(): void {
    this.isPlaying = false;
    this.audioEl?.pause();
    this.stopDemoSynth();
  }

  public resume(): void {
    if (!this.currentTrack) return;
    this.ensureContext();
    this.isPlaying = true;
    if (this.currentTrack.objectUrl && this.audioEl) {
      void this.audioEl.play().catch(() => {});
    } else {
      this.startDemoSynth(this.currentTrack);
    }
  }

  public stop(): void {
    this.stopInternal();
  }

  public seekTo(seconds: number): void {
    if (this.audioEl && this.currentTrack?.objectUrl) {
      this.audioEl.currentTime = seconds;
    }
  }

  public getCurrentTimeSec(): number | null {
    if (this.audioEl && this.currentTrack?.objectUrl) {
      return this.audioEl.currentTime;
    }
    return null;
  }

  public setVolumePercent(percent: number): void {
    this.userVolume = Math.min(1, Math.max(0, percent / 100));
    if (this.masterGain && this.ctx) {
      this.masterGain.gain.setTargetAtTime(this.userVolume, this.ctx.currentTime, 0.03);
    }
  }

  public duck(): void {
    if (!this.duckGain || !this.ctx) return;
    const now = this.ctx.currentTime;
    this.duckGain.gain.cancelScheduledValues(now);
    this.duckGain.gain.setValueAtTime(this.duckGain.gain.value, now);
    this.duckGain.gain.linearRampToValueAtTime(0.24, now + 0.1);
  }

  public restore(): void {
    if (!this.duckGain || !this.ctx) return;
    const now = this.ctx.currentTime;
    this.duckGain.gain.cancelScheduledValues(now);
    this.duckGain.gain.setValueAtTime(this.duckGain.gain.value, now);
    this.duckGain.gain.linearRampToValueAtTime(1.0, now + 1.0);
  }

  public setEqEnabled(enabled: boolean): void {
    this.eqEnabled = enabled;
    if (!this.ctx || this.filters.length === 0) return;
    this.filters.forEach((f, idx) => {
      f.gain.setTargetAtTime(enabled ? (this.storedBandsDb[idx] ?? 0) : 0, this.ctx!.currentTime, 0.03);
    });
  }

  public applyEqPreset(index: number): void {
    const preset = EQ_PRESETS[index] ?? EQ_PRESETS[0];
    this.storedBandsDb = [...preset.gainsDb];
    if (!this.ctx || this.filters.length === 0) return;
    preset.gainsDb.forEach((db, idx) => {
      this.filters[idx]?.gain.setTargetAtTime(this.eqEnabled ? db : 0, this.ctx!.currentTime, 0.04);
    });
  }

  public setBandGain(bandIndex: number, gainDb: number): void {
    const clamped = Math.max(-12, Math.min(12, gainDb));
    this.storedBandsDb[bandIndex] = clamped;
    if (!this.ctx || !this.filters[bandIndex]) return;
    this.filters[bandIndex].gain.setTargetAtTime(this.eqEnabled ? clamped : 0, this.ctx.currentTime, 0.03);
  }

  public setBassBoostEnabled(enabled: boolean): void {
    this.bassBoostEnabled = enabled;
    if (!this.ctx || !this.bassBoostFilter) return;
    this.bassBoostFilter.gain.setTargetAtTime(enabled ? 6.5 : 0, this.ctx.currentTime, 0.04);
  }

  public setBalance(value: number): void {
    this.balance = Math.max(-1, Math.min(1, value));
    if (!this.ctx || !this.panner) return;
    this.panner.pan.setTargetAtTime(this.balance, this.ctx.currentTime, 0.03);
  }

  public getSpectrum(bars: number): { bars: number[]; energy: number } {
    if (!this.analyser || !this.isPlaying) {
      return { bars: new Array<number>(bars).fill(0.06), energy: 0 };
    }
    this.analyser.getByteFrequencyData(this.freqData);
    const out = new Array<number>(bars);
    let sum = 0;
    for (let i = 0; i < bars; i++) {
      const idx = Math.min(this.freqData.length - 1, Math.floor((i / bars) * (this.freqData.length * 0.72)));
      const norm = this.freqData[idx] / 255;
      out[i] = Math.max(0.07, norm);
      sum += norm;
    }
    return { bars: out, energy: sum / bars };
  }

  private startDemoSynth(track: LocalTrackItem): void {
    this.stopDemoSynth();
    const ctx = this.ensureContext();
    const root = track.synthRootHz ?? 164.81;
    const mood = track.synthMood ?? 'rock';
    const intervalMs = mood === 'calm' ? 640 : mood === 'upbeat' ? 320 : 420;
    const scale =
      mood === 'calm'
        ? [1, 1.125, 1.25, 1.5, 1.667, 2]
        : mood === 'upbeat'
          ? [1, 1.25, 1.5, 1.875, 2, 1.5]
          : [1, 1.189, 1.335, 1.498, 1.782, 1.498];

    const triggerNote = () => {
      if (!this.isPlaying || !this.ctx || this.filters.length === 0) return;
      const now = ctx.currentTime;
      const step = this.synthStep++;
      const freq = root * scale[step % scale.length];

      const osc = ctx.createOscillator();
      const env = ctx.createGain();
      osc.type = mood === 'rock' ? 'sawtooth' : mood === 'calm' ? 'sine' : 'triangle';
      osc.frequency.setValueAtTime(freq * (step % 4 === 0 ? 1 : 2), now);

      const toneFilter = ctx.createBiquadFilter();
      toneFilter.type = 'lowpass';
      toneFilter.frequency.setValueAtTime(mood === 'rock' ? 1100 : 1800, now);

      env.gain.setValueAtTime(0.001, now);
      env.gain.exponentialRampToValueAtTime(0.11, now + 0.04);
      env.gain.exponentialRampToValueAtTime(0.0008, now + (intervalMs / 1000) * 0.92);

      osc.connect(toneFilter);
      toneFilter.connect(env);
      env.connect(this.filters[0]);

      osc.start(now);
      osc.stop(now + intervalMs / 1000);

      if (step % 2 === 0) {
        const sub = ctx.createOscillator();
        const subEnv = ctx.createGain();
        sub.type = 'sine';
        sub.frequency.setValueAtTime(root * 0.5, now);
        subEnv.gain.setValueAtTime(0.001, now);
        subEnv.gain.exponentialRampToValueAtTime(0.16, now + 0.02);
        subEnv.gain.exponentialRampToValueAtTime(0.001, now + 0.28);
        sub.connect(subEnv);
        subEnv.connect(this.filters[0]);
        sub.start(now);
        sub.stop(now + 0.3);
      }
    };

    triggerNote();
    this.synthTimer = window.setInterval(triggerNote, intervalMs);
  }

  private stopDemoSynth(): void {
    if (this.synthTimer !== null) {
      window.clearInterval(this.synthTimer);
      this.synthTimer = null;
    }
  }

  private stopInternal(): void {
    this.isPlaying = false;
    this.stopDemoSynth();
    if (this.audioEl) {
      this.audioEl.pause();
      this.audioEl.currentTime = 0;
    }
  }

  public dispose(): void {
    this.stopInternal();
    void this.ctx?.close();
    this.ctx = null;
  }
}
