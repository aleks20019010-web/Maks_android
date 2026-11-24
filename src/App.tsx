import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  FolderPlus,
  Heart,
  ListMusic,
  Mic,
  MicOff,
  Moon,
  Music2,
  Pause,
  Play,
  Repeat,
  Repeat1,
  Search,
  Shuffle,
  SkipBack,
  SkipForward,
  SlidersHorizontal,
  Sparkles,
  ThumbsDown,
  UserRound,
  Volume2,
  VolumeX,
  X,
} from 'lucide-react';
import {
  BUNDLED_ARTIST_ALIAS_COUNT,
  BUNDLED_GENRE_ALIAS_COUNT,
  DEMO_LOCAL_LIBRARY,
  LocalTrackItem,
  matchGenreTracks,
  matchMoodTracks,
  pickPersonalityReply,
  resolveMusicCommand,
} from './core/maksMusicEngine';
import { EQ_BAND_LABELS, EQ_PRESETS, LocalWebAudioEngine } from './core/localAudioEngine';
import { AvatarMode, PlasmaAvatar } from './components/PlasmaAvatar';
import { MirrorSpectrum } from './components/MirrorSpectrum';

type TabId = 'player' | 'library' | 'apk';
type ConsoleModeId = 'player' | 'core';
type PanelTabId = 'eq' | 'queue' | 'wave';
type LibraryFilterId = 'all' | 'liked' | 'artists';
type RepeatMode = 'off' | 'all' | 'one';

interface MaksToast {
  id: number;
  text: string;
  isPending: boolean;
}

interface PendingBlacklist {
  artist: string;
  expiresAt: number;
}

const YES_WORDS = new Set(['да', 'давай', 'конечно', 'ага', 'угу', 'хорошо', 'ок', 'окей']);
const NO_WORDS = new Set(['нет', 'не', 'не надо', 'неважно', 'не важно', 'не хочу', 'отмена']);

function formatClock(sec: number): string {
  const s = Math.max(0, Math.floor(sec));
  const m = Math.floor(s / 60);
  const rem = s % 60;
  return `${m}:${rem.toString().padStart(2, '0')}`;
}

export function App() {
  const audioRef = useRef<LocalWebAudioEngine | null>(null);
  if (!audioRef.current) {
    audioRef.current = new LocalWebAudioEngine();
  }
  const audioEngine = audioRef.current;
  const fileInputRef = useRef<HTMLInputElement | null>(null);
  const audioEnergyRef = useRef<number>(0.05);
  const toastTimerRef = useRef<number | null>(null);

  const [activeTab, setActiveTab] = useState<TabId>('player');
  const [consoleMode, setConsoleMode] = useState<ConsoleModeId>('player');
  const [panelTab, setPanelTab] = useState<PanelTabId>('eq');
  const [libraryFilter, setLibraryFilter] = useState<LibraryFilterId>('all');
  const [librarySearch, setLibrarySearch] = useState<string>('');

  const [library, setLibrary] = useState<LocalTrackItem[]>(DEMO_LOCAL_LIBRARY);
  const [originalQueue, setOriginalQueue] = useState<LocalTrackItem[]>(DEMO_LOCAL_LIBRARY);
  const [queue, setQueue] = useState<LocalTrackItem[]>(DEMO_LOCAL_LIBRARY);
  const [queueIdx, setQueueIdx] = useState<number>(0);
  const [modeLabel, setModeLabel] = useState<string>('Моя волна');

  const [isPlaying, setIsPlaying] = useState<boolean>(false);
  const [positionSec, setPositionSec] = useState<number>(0);
  const [volumePct, setVolumePct] = useState<number>(72);
  const [isShuffle, setIsShuffle] = useState<boolean>(false);
  const [repeatMode, setRepeatMode] = useState<RepeatMode>('off');
  const [sleepMinutes, setSleepMinutes] = useState<number | null>(null);

  const [eqEnabled, setEqEnabled] = useState<boolean>(true);
  const [eqIndex, setEqIndex] = useState<number>(1);
  const [eqBands, setEqBands] = useState<number[]>([...EQ_PRESETS[1].gainsDb]);
  const [bassBoost, setBassBoost] = useState<boolean>(true);
  const [stereoBalance, setStereoBalance] = useState<number>(0);

  const [ttsEnabled, setTtsEnabled] = useState<boolean>(true);
  const [isSpeaking, setIsSpeaking] = useState<boolean>(false);
  const [isListening, setIsListening] = useState<boolean>(false);
  const [emotion, setEmotion] = useState<string>('neutral');
  const [voiceInput, setVoiceInput] = useState<string>('');
  const [toast, setToast] = useState<MaksToast | null>(null);

  const [likedIds, setLikedIds] = useState<Set<string>>(() => new Set(['demo-1', 'demo-4']));
  const [blacklistedIds, setBlacklistedIds] = useState<Set<string>>(() => new Set());
  const [blacklistedArtists, setBlacklistedArtists] = useState<Set<string>>(() => new Set());
  const [artistWeights, setArtistWeights] = useState<Record<string, number>>({
    ария: 1.4,
    кино: 1.1,
    'король и шут': 0.8,
  });
  const skipCounterRef = useRef<Record<string, number>>({});
  const pendingRef = useRef<PendingBlacklist | null>(null);
  const repeatRef = useRef<RepeatMode>(repeatMode);
  repeatRef.current = repeatMode;

  const currentTrack = queue[queueIdx] ?? null;

  useEffect(() => {
    return () => {
      audioEngine.dispose();
      window.speechSynthesis?.cancel();
      if (toastTimerRef.current) window.clearTimeout(toastTimerRef.current);
    };
  }, [audioEngine]);

  useEffect(() => {
    if (sleepMinutes === null) return;
    const id = window.setTimeout(() => {
      if (sleepMinutes <= 1) {
        audioEngine.pause();
        setIsPlaying(false);
        setSleepMinutes(null);
      } else {
        setSleepMinutes(sleepMinutes - 1);
      }
    }, 60_000);
    return () => window.clearTimeout(id);
  }, [sleepMinutes, audioEngine]);

  useEffect(() => {
    if (!isPlaying || !currentTrack) return;
    const id = window.setInterval(() => {
      const realPos = audioEngine.getCurrentTimeSec();
      if (realPos !== null) {
        setPositionSec(realPos);
      } else {
        setPositionSec((prev) => (prev + 0.5 >= currentTrack.durationSec ? 0 : prev + 0.5));
      }
    }, 500);
    return () => window.clearInterval(id);
  }, [isPlaying, currentTrack, audioEngine]);

  const speakLocalTts = useCallback(
    (text: string, rate = 1.04, pitch = 0.96) => {
      if (!ttsEnabled || !('speechSynthesis' in window) || !text.trim()) return;
      window.speechSynthesis.cancel();
      const utter = new SpeechSynthesisUtterance(text);
      utter.lang = 'ru-RU';
      utter.rate = rate;
      utter.pitch = pitch;
      utter.onstart = () => {
        setIsSpeaking(true);
        audioEngine.duck();
      };
      utter.onend = () => {
        setIsSpeaking(false);
        audioEngine.restore();
      };
      utter.onerror = () => {
        setIsSpeaking(false);
        audioEngine.restore();
      };
      window.speechSynthesis.speak(utter);
    },
    [ttsEnabled, audioEngine],
  );

  const showMaksToast = useCallback(
    (replyText: string, replyEmotion: string, isPending = false, rate = 1.04, pitch = 0.96) => {
      setEmotion(replyEmotion);
      if (toastTimerRef.current) window.clearTimeout(toastTimerRef.current);
      setToast({ id: Date.now(), text: replyText, isPending });
      toastTimerRef.current = window.setTimeout(() => {
        setToast(null);
      }, isPending ? 30_000 : 5_500);
      speakLocalTts(replyText, rate, pitch);
    },
    [speakLocalTts],
  );

  const startQueue = useCallback(
    (tracks: LocalTrackItem[], label: string, startIndex = 0, preserveOriginal = false) => {
      if (tracks.length === 0) return false;
      const safeIdx = Math.max(0, Math.min(startIndex, tracks.length - 1));
      if (!preserveOriginal) {
        setOriginalQueue(tracks);
      }
      setQueue(tracks);
      setQueueIdx(safeIdx);
      setModeLabel(label);
      setPositionSec(0);
      setIsPlaying(true);
      audioEngine.applyEqPreset(eqIndex >= 0 ? eqIndex : 0);
      audioEngine.setEqEnabled(eqEnabled);
      audioEngine.setBassBoostEnabled(bassBoost);
      audioEngine.setBalance(stereoBalance);
      audioEngine.playTrack(tracks[safeIdx], () => {
        if (repeatRef.current === 'one') {
          startQueue(tracks, label, safeIdx, true);
          return;
        }
        const nextIndex = safeIdx + 1;
        if (nextIndex < tracks.length) {
          startQueue(tracks, label, nextIndex, true);
        } else if (repeatRef.current === 'all') {
          startQueue(tracks, label, 0, true);
        } else {
          setIsPlaying(false);
        }
      });
      return true;
    },
    [audioEngine, eqIndex, eqEnabled, bassBoost, stereoBalance],
  );

  const buildSmartWave = useCallback(
    (langFilter?: 'russian' | 'not-russian') => {
      const pool = library.filter((t) => {
        if (blacklistedIds.has(t.id)) return false;
        if (blacklistedArtists.has(t.artist.toLowerCase())) return false;
        if (langFilter === 'russian' && !t.isRussian) return false;
        if (langFilter === 'not-russian' && t.isRussian) return false;
        return true;
      });
      return [...pool].sort((a, b) => {
        const wa = (artistWeights[a.artist.toLowerCase()] ?? 0) + (likedIds.has(a.id) ? 1.5 : 0) + Math.random() * 1.4;
        const wb = (artistWeights[b.artist.toLowerCase()] ?? 0) + (likedIds.has(b.id) ? 1.5 : 0) + Math.random() * 1.4;
        return wb - wa;
      });
    },
    [library, blacklistedIds, blacklistedArtists, artistWeights, likedIds],
  );

  const handleNext = useCallback(
    (isUserSkip: boolean) => {
      if (queue.length === 0) return;
      const cur = queue[queueIdx];
      if (isUserSkip && cur) {
        const key = cur.artist.toLowerCase();
        const nextCount = (skipCounterRef.current[key] ?? 0) + 1;
        skipCounterRef.current[key] = nextCount;
        setArtistWeights((w) => ({ ...w, [key]: Math.max(-2, (w[key] ?? 0) - 0.1) }));

        if (nextCount >= 3) {
          skipCounterRef.current[key] = 0;
          pendingRef.current = { artist: cur.artist, expiresAt: Date.now() + 30_000 };
          const ask = pickPersonalityReply('APPRAISAL_SKIP_3X', { artist: cur.artist });
          showMaksToast(ask.displayText, ask.emotion, true, ask.speechRate, ask.speechPitch);
        }
      }
      if (!isUserSkip && repeatMode === 'one') {
        startQueue(queue, modeLabel, queueIdx, true);
        return;
      }
      const nextIdx = (queueIdx + 1) % queue.length;
      if (!isUserSkip && nextIdx === 0 && repeatMode === 'off') {
        audioEngine.pause();
        setIsPlaying(false);
        return;
      }
      startQueue(queue, modeLabel, nextIdx, true);
    },
    [queue, queueIdx, modeLabel, repeatMode, startQueue, showMaksToast, audioEngine],
  );

  const handlePrev = useCallback(() => {
    if (queue.length === 0) return;
    if (positionSec > 4) {
      setPositionSec(0);
      audioEngine.seekTo(0);
      return;
    }
    const prevIdx = (queueIdx - 1 + queue.length) % queue.length;
    startQueue(queue, modeLabel, prevIdx, true);
  }, [queue, queueIdx, modeLabel, positionSec, audioEngine, startQueue]);

  const handleTogglePlay = useCallback(() => {
    if (!currentTrack) {
      startQueue(buildSmartWave(), 'Моя волна');
      return;
    }
    if (isPlaying) {
      audioEngine.pause();
      setIsPlaying(false);
    } else {
      if (positionSec === 0) {
        startQueue(queue, modeLabel, queueIdx, true);
      } else {
        audioEngine.resume();
        setIsPlaying(true);
      }
    }
  }, [currentTrack, isPlaying, positionSec, queue, modeLabel, queueIdx, audioEngine, startQueue, buildSmartWave]);

  const handleSeek = useCallback(
    (sec: number) => {
      const clamped = Math.max(0, Math.min(currentTrack?.durationSec ?? 0, sec));
      setPositionSec(clamped);
      audioEngine.seekTo(clamped);
    },
    [currentTrack, audioEngine],
  );

  const handleToggleShuffle = useCallback(() => {
    const nextShuffle = !isShuffle;
    setIsShuffle(nextShuffle);
    if (queue.length <= 1) return;
    const cur = queue[queueIdx];
    if (nextShuffle) {
      const rest = queue.filter((_, i) => i !== queueIdx).sort(() => Math.random() - 0.5);
      const nextQ = cur ? [cur, ...rest] : rest;
      setQueue(nextQ);
      setQueueIdx(0);
    } else {
      const idx = cur ? originalQueue.findIndex((t) => t.id === cur.id) : 0;
      setQueue(originalQueue);
      setQueueIdx(Math.max(0, idx));
    }
  }, [isShuffle, queue, queueIdx, originalQueue]);

  const handleCycleRepeat = useCallback(() => {
    setRepeatMode((prev) => (prev === 'off' ? 'all' : prev === 'all' ? 'one' : 'off'));
  }, []);

  const handleToggleLikeTrack = useCallback((track: LocalTrackItem) => {
    setLikedIds((prev) => {
      const next = new Set(prev);
      const added = !next.has(track.id);
      if (added) {
        next.add(track.id);
        skipCounterRef.current[track.artist.toLowerCase()] = 0;
        setArtistWeights((w) => ({
          ...w,
          [track.artist.toLowerCase()]: Math.min(3, (w[track.artist.toLowerCase()] ?? 0) + 0.5),
        }));
      } else {
        next.delete(track.id);
      }
      return next;
    });
  }, []);

  const handleDislikeCurrent = useCallback(() => {
    if (!currentTrack) return;
    setBlacklistedIds((prev) => new Set(prev).add(currentTrack.id));
    setLikedIds((prev) => {
      const next = new Set(prev);
      next.delete(currentTrack.id);
      return next;
    });
    const key = currentTrack.artist.toLowerCase();
    setArtistWeights((w) => ({ ...w, [key]: Math.max(-2, (w[key] ?? 0) - 0.8) }));
    handleNext(false);
  }, [currentTrack, handleNext]);

  const handleToggleEqEnabled = useCallback(
    (enabled: boolean) => {
      setEqEnabled(enabled);
      audioEngine.setEqEnabled(enabled);
    },
    [audioEngine],
  );

  const handleSelectEq = useCallback(
    (idx: number) => {
      setEqIndex(idx);
      setEqBands([...EQ_PRESETS[idx].gainsDb]);
      audioEngine.applyEqPreset(idx);
    },
    [audioEngine],
  );

  const handleChangeEqBand = useCallback(
    (bandIdx: number, db: number) => {
      setEqIndex(-1);
      setEqBands((prev) => {
        const next = [...prev];
        next[bandIdx] = db;
        return next;
      });
      audioEngine.setBandGain(bandIdx, db);
    },
    [audioEngine],
  );

  const handleToggleBassBoost = useCallback(
    (enabled: boolean) => {
      setBassBoost(enabled);
      audioEngine.setBassBoostEnabled(enabled);
    },
    [audioEngine],
  );

  const handleBalanceChange = useCallback(
    (val: number) => {
      const snapped = Math.abs(val) < 0.05 ? 0 : val;
      setStereoBalance(snapped);
      audioEngine.setBalance(snapped);
    },
    [audioEngine],
  );

  const executeCommand = useCallback(
    (rawText: string) => {
      const trimmed = rawText.trim();
      if (!trimmed) return;

      const low = trimmed.toLowerCase();
      const pending = pendingRef.current;
      if (pending && Date.now() < pending.expiresAt) {
        if (YES_WORDS.has(low)) {
          pendingRef.current = null;
          setBlacklistedArtists((prev) => new Set(prev).add(pending.artist.toLowerCase()));
          const reply = pickPersonalityReply('APPRAISAL_BLACKLIST_CONFIRMED', { artist: pending.artist });
          showMaksToast(reply.displayText, reply.emotion, false, reply.speechRate, reply.speechPitch);
          if (currentTrack?.artist.toLowerCase() === pending.artist.toLowerCase()) {
            handleNext(false);
          }
          return;
        }
        if (NO_WORDS.has(low)) {
          pendingRef.current = null;
          const reply = pickPersonalityReply('APPRAISAL_BLACKLIST_DECLINED', { artist: pending.artist });
          showMaksToast(reply.displayText, reply.emotion, false, reply.speechRate, reply.speechPitch);
          return;
        }
      }

      const res = resolveMusicCommand(trimmed, library);
      let eventKey = 'MUSIC_PLAY';
      let slots: Record<string, string> = {};

      switch (res.intent) {
        case 'MUSIC_WAVE':
        case 'MUSIC_PLAY': {
          if (res.entityType === 'artist' && res.entityValue) {
            const matched = library.filter(
              (t) =>
                !blacklistedIds.has(t.id) &&
                t.artist.toLowerCase().includes(res.entityValue!.toLowerCase()),
            );
            if (matched.length > 0) {
              startQueue(matched, res.entityValue);
              eventKey = 'MUSIC_ARTIST_PLAY';
              slots = { artist: res.entityValue };
            } else {
              eventKey = 'MUSIC_NOT_FOUND';
              slots = { query: res.entityValue };
            }
          } else if (res.entityType === 'genre' && res.entityValue) {
            const matched = matchGenreTracks(res.entityValue, library).filter((t) => !blacklistedIds.has(t.id));
            if (matched.length > 0) {
              startQueue(matched, `Жанр: ${res.entityValue}`);
              eventKey = 'MUSIC_GENRE_PLAY';
              slots = { genre: res.entityValue };
            } else {
              eventKey = 'MUSIC_NOT_FOUND';
              slots = { query: res.entityValue };
            }
          } else if (res.entityType === 'mood' && res.entityValue) {
            const matched = matchMoodTracks(res.entityValue, library).filter((t) => !blacklistedIds.has(t.id));
            if (matched.length > 0) {
              startQueue(matched, `Настроение: ${res.entityValue}`);
              eventKey = 'MUSIC_MOOD_PLAY';
              slots = { mood: res.entityValue };
            } else {
              eventKey = 'MUSIC_NOT_FOUND';
              slots = { query: res.entityValue };
            }
          } else if (res.entityType === 'track' && res.entityValue) {
            const q = res.entityValue.toLowerCase();
            const matched = library.filter((t) => `${t.artist} ${t.title}`.toLowerCase().includes(q));
            if (matched.length > 0) {
              startQueue(matched, matched[0].title);
              eventKey = 'MUSIC_TRACK_PLAY';
              slots = { track: `${matched[0].artist} — ${matched[0].title}` };
            } else {
              eventKey = 'MUSIC_NOT_FOUND';
              slots = { query: res.entityValue };
            }
          } else {
            const wave = buildSmartWave(res.language);
            const label =
              res.language === 'russian'
                ? 'Русская волна'
                : res.language === 'not-russian'
                  ? 'Зарубежная волна'
                  : 'Моя волна';
            startQueue(wave, label);
            eventKey = 'MUSIC_PLAY';
          }
          break;
        }
        case 'MUSIC_LOCAL_LIKED': {
          const liked = library.filter((t) => likedIds.has(t.id));
          if (liked.length > 0) {
            startQueue(liked, 'Мои лайки');
            eventKey = 'MUSIC_MY_LIKES';
          } else {
            eventKey = 'MUSIC_LIKES_EMPTY';
          }
          break;
        }
        case 'MUSIC_NEXT':
          handleNext(true);
          return;
        case 'MUSIC_PREV':
          handlePrev();
          return;
        case 'MUSIC_PAUSE':
          audioEngine.pause();
          setIsPlaying(false);
          return;
        case 'MUSIC_RESUME':
          handleTogglePlay();
          return;
        case 'MUSIC_STOP':
          audioEngine.stop();
          setIsPlaying(false);
          setPositionSec(0);
          return;
        case 'MUSIC_LIKE':
          if (currentTrack) {
            if (!likedIds.has(currentTrack.id)) handleToggleLikeTrack(currentTrack);
            eventKey = 'MUSIC_LIKED';
            slots = { track: `${currentTrack.artist} — ${currentTrack.title}` };
          } else {
            eventKey = 'MUSIC_NOTHING_PLAYING';
          }
          break;
        case 'MUSIC_DISLIKE':
          if (currentTrack) {
            handleDislikeCurrent();
            eventKey = 'MUSIC_DISLIKED';
            slots = { track: `${currentTrack.artist} — ${currentTrack.title}` };
          } else {
            eventKey = 'MUSIC_NOTHING_PLAYING';
          }
          break;
        case 'MUSIC_NOW_PLAYING':
          if (currentTrack) {
            eventKey = 'MUSIC_WHAT_PLAYING';
            slots = { track: `${currentTrack.artist} — ${currentTrack.title}` };
          } else {
            eventKey = 'MUSIC_NOTHING_PLAYING';
          }
          break;
        case 'VOLUME_UP': {
          const v = Math.min(100, volumePct + 15);
          setVolumePct(v);
          audioEngine.setVolumePercent(v);
          return;
        }
        case 'VOLUME_DOWN': {
          const v = Math.max(0, volumePct - 15);
          setVolumePct(v);
          audioEngine.setVolumePercent(v);
          return;
        }
        case 'VOLUME_SET': {
          const v = res.volumeLevel ?? 50;
          setVolumePct(v);
          audioEngine.setVolumePercent(v);
          eventKey = 'VOLUME_SET';
          slots = { level: String(v) };
          break;
        }
        default: {
          const matched = library.filter((t) =>
            `${t.artist} ${t.title}`.toLowerCase().includes(res.normalizedText),
          );
          if (matched.length > 0) {
            startQueue(matched, matched[0].title);
            eventKey = 'MUSIC_TRACK_PLAY';
            slots = { track: `${matched[0].artist} — ${matched[0].title}` };
          } else {
            eventKey = 'MUSIC_NOT_FOUND';
            slots = { query: trimmed };
          }
        }
      }

      const reply = pickPersonalityReply(eventKey, slots);
      showMaksToast(reply.displayText, reply.emotion, false, reply.speechRate, reply.speechPitch);
    },
    [
      library,
      blacklistedIds,
      likedIds,
      currentTrack,
      volumePct,
      audioEngine,
      startQueue,
      buildSmartWave,
      handleNext,
      handlePrev,
      handleTogglePlay,
      handleToggleLikeTrack,
      handleDislikeCurrent,
      showMaksToast,
    ],
  );

  const handleBrowserVoice = useCallback(() => {
    interface BrowserSpeechRec {
      lang: string;
      interimResults: boolean;
      onstart: (() => void) | null;
      onresult: ((ev: { results: { [idx: number]: { [idx: number]: { transcript?: string } } } }) => void) | null;
      onend: (() => void) | null;
      onerror: (() => void) | null;
      start: () => void;
    }
    const win = window as unknown as {
      SpeechRecognition?: new () => BrowserSpeechRec;
      webkitSpeechRecognition?: new () => BrowserSpeechRec;
    };
    const SpeechRec = win.SpeechRecognition || win.webkitSpeechRecognition;

    if (!SpeechRec) {
      showMaksToast('На Android работает встроенный оффлайн Vosk. В этом браузере введите команду ниже.', 'curious');
      return;
    }

    const rec = new SpeechRec();
    rec.lang = 'ru-RU';
    rec.interimResults = false;
    rec.onstart = () => {
      setIsListening(true);
      audioEngine.duck();
    };
    rec.onresult = (ev) => {
      const text = ev.results[0]?.[0]?.transcript ?? '';
      if (text) executeCommand(text);
    };
    rec.onend = () => {
      setIsListening(false);
      audioEngine.restore();
    };
    rec.onerror = () => {
      setIsListening(false);
      audioEngine.restore();
    };
    rec.start();
  }, [audioEngine, executeCommand, showMaksToast]);

  const handleUploadLocalFiles = useCallback(
    (files: FileList | null) => {
      if (!files || files.length === 0) return;
      const added: LocalTrackItem[] = [];
      Array.from(files).forEach((file, idx) => {
        if (!file.type.startsWith('audio/') && !/\.(mp3|flac|wav|ogg|m4a)$/i.test(file.name)) return;
        const clean = file.name.replace(/\.[^.]+$/, '').replace(/_/g, ' ');
        const parts = clean.split(/\s[-—]\s/);
        const artist = parts.length >= 2 ? parts[0].trim() : 'Локальный файл';
        const title = parts.length >= 2 ? parts.slice(1).join(' - ').trim() : clean;
        const isRu = /[а-яё]/i.test(`${artist} ${title}`);
        added.push({
          id: `local-${Date.now()}-${idx}`,
          artist,
          title,
          album: 'Устройство',
          genre: isRu ? 'rusrock' : 'rock',
          durationSec: 210,
          isRussian: isRu,
          objectUrl: URL.createObjectURL(file),
        });
      });

      if (added.length > 0) {
        const nextLib = [...added, ...library];
        setLibrary(nextLib);
        startQueue(added, 'Мои файлы', 0);
        showMaksToast(`Добавлено локальных треков: ${added.length}`, 'happy');
      }
    },
    [library, startQueue, showMaksToast],
  );

  const filteredLibrary = useMemo(() => {
    const base = libraryFilter === 'liked' ? library.filter((t) => likedIds.has(t.id)) : library;
    const q = librarySearch.trim().toLowerCase();
    if (!q) return base;
    return base.filter(
      (t) =>
        t.title.toLowerCase().includes(q) ||
        t.artist.toLowerCase().includes(q) ||
        t.album.toLowerCase().includes(q),
    );
  }, [library, libraryFilter, likedIds, librarySearch]);

  const artistsGrouped = useMemo(() => {
    const map = new Map<string, LocalTrackItem[]>();
    filteredLibrary.forEach((t) => {
      const list = map.get(t.artist) ?? [];
      list.push(t);
      map.set(t.artist, list);
    });
    return Array.from(map.entries()).sort((a, b) => b[1].length - a[1].length);
  }, [filteredLibrary]);

  const avatarMode: AvatarMode = useMemo(() => {
    if (isSpeaking) return 'speaking';
    if (isListening) return 'listening';
    if (isPlaying) return 'music';
    if (emotion === 'happy' || emotion === 'good') return 'happy';
    if (emotion === 'curious') return 'curious';
    return 'idle';
  }, [isSpeaking, isListening, isPlaying, emotion]);

  const durationSec = currentTrack?.durationSec ?? 0;
  const progressFraction = durationSec > 0 ? Math.min(1, Math.max(0, positionSec / durationSec)) : 0;
  const isCurrentLiked = currentTrack ? likedIds.has(currentTrack.id) : false;

  // Геометрия круговой неоновой дуги прогресса (SVG радиус 108)
  const dialRadius = 108;
  const dialCircumference = 2 * Math.PI * dialRadius;
  const dialOffset = dialCircumference * (1 - progressFraction);
  const tipAngleRad = (-90 + progressFraction * 360) * (Math.PI / 180);
  const tipX = 128 + Math.cos(tipAngleRad) * dialRadius;
  const tipY = 128 + Math.sin(tipAngleRad) * dialRadius;

  const balPct = Math.round(stereoBalance * 100);
  const balLabel = balPct === 0 ? '0' : balPct < 0 ? `L ${Math.abs(balPct)}` : `R ${balPct}`;

  return (
    <div className="min-h-screen bg-[radial-gradient(ellipse_at_top,_#14172b_0%,_#0a0b13_52%,_#06070d_100%)] text-[#f5f7ff] flex flex-col">
      {/* Скрытый загрузчик локальных аудиофайлов */}
      <input
        ref={fileInputRef}
        type="file"
        accept="audio/*,.mp3,.flac,.wav,.ogg,.m4a"
        multiple
        className="hidden"
        onChange={(e) => handleUploadLocalFiles(e.target.files)}
      />

      {/* Верхняя панель (3 зоны) */}
      <header className="sticky top-0 z-30 border-b border-white/[0.06] bg-[#0a0b13]/85 backdrop-blur-xl">
        <div className="mx-auto flex max-w-6xl items-center justify-between gap-8 px-5 py-3.5">
          <a
            href="#player"
            onClick={(e) => {
              e.preventDefault();
              setActiveTab('player');
            }}
            className="text-base font-bold tracking-tight text-[#f5f7ff] whitespace-nowrap shrink-0"
          >
            Макс Музыка
          </a>

          <nav className="flex items-center gap-6 text-sm font-medium text-[#77809e]">
            {(
              [
                ['player', 'Плеер'],
                ['library', 'Медиатека'],
                ['apk', 'Android APK'],
              ] as const
            ).map(([id, label]) => (
              <button
                key={id}
                type="button"
                onClick={() => setActiveTab(id)}
                className={`py-1 transition-colors whitespace-nowrap shrink-0 cursor-pointer border-b-2 ${
                  activeTab === id
                    ? 'border-[#8b5cf6] text-[#f5f7ff]'
                    : 'border-transparent hover:text-[#c5cce2]'
                }`}
              >
                {label}
              </button>
            ))}
          </nav>

          <button
            type="button"
            onClick={() => fileInputRef.current?.click()}
            className="flex items-center gap-2 rounded-xl border border-white/10 bg-[#141726] px-4 py-2 text-xs font-medium text-[#f5f7ff] hover:border-[#8b5cf6]/50 hover:bg-[#1a1e32] transition-colors whitespace-nowrap shrink-0 cursor-pointer"
          >
            <FolderPlus className="h-4 w-4 text-[#a78bfa]" />
            Добавить треки
          </button>
        </div>
      </header>

      {/* Всплывающий тост ответа Макса */}
      {toast && (
        <div className="fixed top-20 left-1/2 -translate-x-1/2 z-40 w-[min(92vw,480px)] rounded-2xl border border-[#8b5cf6]/40 bg-[#131626]/95 px-4 py-3 shadow-2xl shadow-black/60 backdrop-blur-xl">
          <div className="flex items-start justify-between gap-3">
            <p className="text-sm leading-relaxed text-[#f5f7ff]">{toast.text}</p>
            <button
              type="button"
              onClick={() => setToast(null)}
              aria-label="Закрыть"
              className="text-[#77809e] hover:text-white cursor-pointer shrink-0"
            >
              <X className="h-4 w-4" />
            </button>
          </div>
          {toast.isPending && (
            <div className="mt-3 flex gap-2">
              <button
                type="button"
                onClick={() => {
                  executeCommand('да');
                  setToast(null);
                }}
                className="flex-1 rounded-xl bg-[#8b5cf6] py-1.5 text-xs font-semibold text-white hover:bg-[#7c3aed] cursor-pointer"
              >
                Да, убрать
              </button>
              <button
                type="button"
                onClick={() => {
                  executeCommand('нет');
                  setToast(null);
                }}
                className="flex-1 rounded-xl border border-white/10 bg-[#1a1e30] py-1.5 text-xs font-medium text-[#c5cce2] hover:text-white cursor-pointer"
              >
                Оставить
              </button>
            </div>
          )}
        </div>
      )}

      {/* Основной контент */}
      <main className="mx-auto w-full max-w-6xl flex-1 px-5 py-6">
        {activeTab === 'player' && (
          <div className="grid grid-cols-1 gap-6 lg:grid-cols-12 items-stretch">
            {/* Левая консоль: переключение «Плеер ↔ Ядро Макса» */}
            <section className="lg:col-span-6 flex flex-col justify-between rounded-[28px] border border-white/[0.08] bg-[#10121f]/90 p-6 sm:p-7 shadow-2xl shadow-black/50">
              {/* Шапка с переключателем Плеер ↔ Ядро Макса */}
              <div className="flex items-center justify-between gap-3">
                <div className="inline-flex items-center rounded-xl border border-white/[0.08] bg-[#0b0d17] p-1">
                  <button
                    type="button"
                    onClick={() => setConsoleMode('player')}
                    className={`flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-xs font-medium transition-all whitespace-nowrap shrink-0 cursor-pointer ${
                      consoleMode === 'player'
                        ? 'border border-[#a78bfa]/55 bg-gradient-to-r from-[#8b5cf6]/35 to-[#4fd1ff]/20 text-white shadow-[0_0_14px_rgba(139,92,246,0.28)]'
                        : 'border border-transparent text-[#77809e] hover:text-[#c5cce2]'
                    }`}
                  >
                    <Music2 className="h-3.5 w-3.5 text-[#4fd1ff]" />
                    Плеер
                  </button>
                  <button
                    type="button"
                    onClick={() => setConsoleMode('core')}
                    className={`flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-xs font-medium transition-all whitespace-nowrap shrink-0 cursor-pointer ${
                      consoleMode === 'core'
                        ? 'border border-[#a78bfa]/55 bg-gradient-to-r from-[#8b5cf6]/35 to-[#4fd1ff]/20 text-white shadow-[0_0_14px_rgba(139,92,246,0.28)]'
                        : 'border border-transparent text-[#77809e] hover:text-[#c5cce2]'
                    }`}
                  >
                    <Sparkles className="h-3.5 w-3.5 text-[#a78bfa]" />
                    Ядро Макса
                  </button>
                </div>

                <div className="flex items-center gap-1.5">
                  <button
                    type="button"
                    onClick={() => setTtsEnabled((v) => !v)}
                    aria-label="Озвучка Макса"
                    className={`flex h-9 w-9 items-center justify-center rounded-full border transition-colors cursor-pointer ${
                      ttsEnabled
                        ? 'border-[#8b5cf6]/40 bg-[#8b5cf6]/15 text-[#4fd1ff]'
                        : 'border-white/10 bg-[#151828] text-[#77809e]'
                    }`}
                  >
                    {ttsEnabled ? <Volume2 className="h-4 w-4" /> : <VolumeX className="h-4 w-4" />}
                  </button>
                  <button
                    type="button"
                    onClick={handleBrowserVoice}
                    aria-label="Голосовая команда"
                    className={`flex h-9 w-9 items-center justify-center rounded-full border transition-colors cursor-pointer ${
                      isListening
                        ? 'border-rose-400/60 bg-rose-500/20 text-rose-300'
                        : 'border-white/10 bg-[#151828] text-[#c5cce2] hover:border-[#8b5cf6]/50'
                    }`}
                  >
                    {isListening ? <Mic className="h-4 w-4" /> : <MicOff className="h-4 w-4" />}
                  </button>
                </div>
              </div>

              {consoleMode === 'player' ? (
                <>
                  {/* Круговой прецизионный циферблат с плазменной сферой */}
                  <div className="my-5 flex flex-col items-center">
                    <div className="relative h-64 w-64 flex items-center justify-center">
                      <svg viewBox="0 0 256 256" className="absolute inset-0 h-full w-full pointer-events-none">
                        <defs>
                          <linearGradient id="dialGrad" x1="0%" y1="0%" x2="100%" y2="100%">
                            <stop offset="0%" stopColor="#a78bfa" />
                            <stop offset="50%" stopColor="#8b5cf6" />
                            <stop offset="100%" stopColor="#4fd1ff" />
                          </linearGradient>
                        </defs>
                        {/* 60 тонких радиальных штрихов */}
                        {Array.from({ length: 60 }).map((_, i) => {
                          const deg = -90 + i * 6;
                          const rad = (deg * Math.PI) / 180;
                          const isMajor = i % 5 === 0;
                          const r1 = isMajor ? 116 : 119;
                          const r2 = 124;
                          return (
                            <line
                              key={i}
                              x1={128 + Math.cos(rad) * r1}
                              y1={128 + Math.sin(rad) * r1}
                              x2={128 + Math.cos(rad) * r2}
                              y2={128 + Math.sin(rad) * r2}
                              stroke={isMajor ? 'rgba(255,255,255,0.22)' : 'rgba(255,255,255,0.09)'}
                              strokeWidth={isMajor ? 1.4 : 1}
                              strokeLinecap="round"
                            />
                          );
                        })}
                        {/* Базовое кольцо */}
                        <circle
                          cx="128"
                          cy="128"
                          r={dialRadius}
                          fill="none"
                          stroke="#1a1e32"
                          strokeWidth="3.2"
                        />
                        {/* Активная дуга прогресса */}
                        <circle
                          cx="128"
                          cy="128"
                          r={dialRadius}
                          fill="none"
                          stroke="url(#dialGrad)"
                          strokeWidth="3.4"
                          strokeLinecap="round"
                          strokeDasharray={dialCircumference}
                          strokeDashoffset={dialOffset}
                          transform="rotate(-90 128 128)"
                        />
                        {/* Светящаяся точка прогресса */}
                        {progressFraction > 0.005 && (
                          <>
                            <circle cx={tipX} cy={tipY} r="9" fill="rgba(79,209,255,0.28)" />
                            <circle cx={tipX} cy={tipY} r="3.8" fill="#ffffff" />
                          </>
                        )}
                      </svg>

                      <div className="relative h-48 w-48 rounded-full border border-white/10 bg-[radial-gradient(circle,_#14182b_0%,_#0a0c16_100%)] flex items-center justify-center shadow-inner">
                        <PlasmaAvatar mode={avatarMode} audioLevelRef={audioEnergyRef} onClick={handleBrowserVoice} />
                      </div>
                    </div>

                    <div className="mt-2 font-mono-tabular text-xs text-[#77809e]">
                      <span className="text-[#c5cce2]">{formatClock(positionSec)}</span>
                      <span className="mx-1">/</span>
                      <span>{formatClock(durationSec)}</span>
                    </div>
                  </div>

                  {/* Название трека + Лайк / Дизлайк */}
                  <div className="flex items-center justify-between gap-3">
                    <div className="min-w-0">
                      <h1 className="text-xl font-bold tracking-tight text-[#f5f7ff] truncate">
                        {currentTrack ? currentTrack.title : 'Включите Мою волну'}
                      </h1>
                      <p className="mt-0.5 text-sm text-[#77809e] truncate">
                        {currentTrack
                          ? `${currentTrack.artist} · ${modeLabel}${sleepMinutes !== null ? ` · Сон ${sleepMinutes} мин` : ''}`
                          : 'Выберите трек в медиатеке'}
                      </p>
                    </div>
                    <div className="flex items-center gap-1 shrink-0">
                      <button
                        type="button"
                        onClick={handleDislikeCurrent}
                        aria-label="Не нравится"
                        className="flex h-10 w-10 items-center justify-center rounded-full text-[#77809e] hover:bg-white/5 hover:text-amber-300 transition-colors cursor-pointer"
                      >
                        <ThumbsDown className="h-4 w-4" />
                      </button>
                      <button
                        type="button"
                        onClick={() => currentTrack && handleToggleLikeTrack(currentTrack)}
                        aria-label="Нравится"
                        className={`flex h-10 w-10 items-center justify-center rounded-full transition-colors cursor-pointer ${
                          isCurrentLiked ? 'text-rose-400' : 'text-[#c5cce2] hover:bg-white/5 hover:text-rose-300'
                        }`}
                      >
                        <Heart className={`h-5 w-5 ${isCurrentLiked ? 'fill-rose-400' : ''}`} />
                      </button>
                    </div>
                  </div>

                  {/* 64-полосная волна + тонкий неоновый ползунок */}
                  <div className="mt-4 space-y-2">
                    <MirrorSpectrum
                      audioEngine={audioEngine}
                      isPlaying={isPlaying}
                      isVoiceActive={isSpeaking || isListening}
                      progress={progressFraction}
                      trackSeed={currentTrack ? currentTrack.id.length * 13 + queueIdx : 7}
                      onSeekFraction={(f) => handleSeek(f * durationSec)}
                      audioEnergyRef={audioEnergyRef}
                    />
                    <input
                      type="range"
                      min={0}
                      max={Math.max(1, durationSec)}
                      step={1}
                      value={Math.min(positionSec, durationSec)}
                      onChange={(e) => handleSeek(Number(e.target.value))}
                      aria-label="Позиция трека"
                      className="w-full h-1.5 rounded-full accent-[#8b5cf6] cursor-pointer"
                    />
                    <div className="flex items-center justify-between font-mono-tabular text-[11px] text-[#77809e]">
                      <span>{formatClock(positionSec)}</span>
                      <span>{formatClock(durationSec)}</span>
                    </div>
                  </div>

                  {/* Тактильные тёмные кнопки управления + светящаяся орбитальная Play/Pause */}
                  <div className="mt-5 flex items-center justify-between px-2">
                    <button
                      type="button"
                      onClick={handleToggleShuffle}
                      aria-label="Перемешать"
                      className={`flex h-10 w-10 items-center justify-center rounded-full border transition-colors cursor-pointer ${
                        isShuffle
                          ? 'border-[#a78bfa]/60 bg-[#8b5cf6]/20 text-[#4fd1ff]'
                          : 'border-white/[0.08] bg-[#141726] text-[#77809e] hover:text-white'
                      }`}
                    >
                      <Shuffle className="h-4 w-4" />
                    </button>
                    <button
                      type="button"
                      onClick={handlePrev}
                      aria-label="Предыдущий трек"
                      className="flex h-12 w-12 items-center justify-center rounded-full border border-white/[0.08] bg-[#141726] text-[#f5f7ff] hover:border-white/20 transition-colors cursor-pointer"
                    >
                      <SkipBack className="h-5 w-5" />
                    </button>
                    <button
                      type="button"
                      onClick={handleTogglePlay}
                      aria-label={isPlaying ? 'Пауза' : 'Играть'}
                      className="relative flex h-[70px] w-[70px] items-center justify-center rounded-full bg-[radial-gradient(circle,_#282747_0%,_#15172b_100%)] text-white ring-2 ring-[#8b5cf6] shadow-[0_0_28px_rgba(139,92,246,0.52)] hover:scale-[1.03] active:scale-[0.98] transition-transform cursor-pointer"
                    >
                      {isPlaying ? <Pause className="h-7 w-7 fill-white" /> : <Play className="h-7 w-7 fill-white ml-0.5" />}
                    </button>
                    <button
                      type="button"
                      onClick={() => handleNext(true)}
                      aria-label="Следующий трек"
                      className="flex h-12 w-12 items-center justify-center rounded-full border border-white/[0.08] bg-[#141726] text-[#f5f7ff] hover:border-white/20 transition-colors cursor-pointer"
                    >
                      <SkipForward className="h-5 w-5" />
                    </button>
                    <button
                      type="button"
                      onClick={handleCycleRepeat}
                      aria-label="Повтор"
                      className={`flex h-10 w-10 items-center justify-center rounded-full border transition-colors cursor-pointer ${
                        repeatMode !== 'off'
                          ? 'border-[#a78bfa]/60 bg-[#8b5cf6]/20 text-[#4fd1ff]'
                          : 'border-white/[0.08] bg-[#141726] text-[#77809e] hover:text-white'
                      }`}
                    >
                      {repeatMode === 'one' ? <Repeat1 className="h-4 w-4" /> : <Repeat className="h-4 w-4" />}
                    </button>
                  </div>

                  {/* Сдвоенная стеклянная панель быстрых действий */}
                  <div className="mt-5 grid grid-cols-2 divide-x divide-white/[0.08] overflow-hidden rounded-2xl border border-white/[0.08] bg-[#131624]">
                    <button
                      type="button"
                      onClick={() => startQueue(buildSmartWave(), 'Моя волна')}
                      className="flex items-center justify-center gap-2 py-3 text-xs font-medium text-[#f5f7ff] hover:bg-white/[0.04] transition-colors cursor-pointer"
                    >
                      <Sparkles className="h-4 w-4 text-[#a78bfa]" />
                      Моя волна
                    </button>
                    <button
                      type="button"
                      onClick={() => setConsoleMode('core')}
                      className="flex items-center justify-center gap-2 py-3 text-xs font-medium text-[#f5f7ff] hover:bg-white/[0.04] transition-colors cursor-pointer"
                    >
                      <Mic className="h-4 w-4 text-[#4fd1ff]" />
                      Ядро Макса
                    </button>
                  </div>
                </>
              ) : (
                /* Выделенный экран «Ядро Макса» */
                <div className="my-4 flex flex-1 flex-col items-center justify-between gap-5">
                  <div className="flex flex-col items-center text-center">
                    <div className="relative h-60 w-60 rounded-full border border-white/10 bg-[radial-gradient(circle,_#161a30_0%,_#090b15_100%)] flex items-center justify-center shadow-[0_0_54px_rgba(139,92,246,0.22)]">
                      <PlasmaAvatar mode={avatarMode} audioLevelRef={audioEnergyRef} onClick={handleBrowserVoice} />
                    </div>
                    <h2 className="mt-4 text-lg font-semibold text-[#f5f7ff]">
                      {isSpeaking
                        ? 'Макс отвечает…'
                        : isListening
                          ? 'Слушаю команду…'
                          : 'Нажмите на ядро или скажите команду'}
                    </h2>
                    <p className="mt-1 text-xs text-[#77809e]">
                      Вшито оффлайн: {BUNDLED_ARTIST_ALIAS_COUNT} алиасов артистов · {BUNDLED_GENRE_ALIAS_COUNT} жанров
                    </p>
                  </div>

                  <div className="w-full space-y-3">
                    <MirrorSpectrum
                      audioEngine={audioEngine}
                      isPlaying={isPlaying}
                      isVoiceActive={isSpeaking || isListening}
                      progress={1}
                      trackSeed={42}
                      onSeekFraction={() => {}}
                      audioEnergyRef={audioEnergyRef}
                    />

                    {/* Быстрые проверки вшитых алиасов и волн */}
                    <div className="flex flex-wrap gap-2">
                      {[
                        ['Включи Горшка', 'включи Горшка'],
                        ['Включи Цоя', 'включи Цоя'],
                        ['Включи Метлу', 'включи метлу'],
                        ['Русский рок', 'включи русский рок'],
                        ['Моя волна', 'включи мою волну'],
                        ['Мои лайки', 'включи мои лайки'],
                      ].map(([label, cmd]) => (
                        <button
                          key={label}
                          type="button"
                          onClick={() => executeCommand(cmd)}
                          className="rounded-xl border border-white/[0.08] bg-[#141726] px-3 py-1.5 text-xs text-[#c5cce2] hover:border-[#8b5cf6]/55 hover:text-white transition-colors cursor-pointer"
                        >
                          {label}
                        </button>
                      ))}
                    </div>

                    {/* Компактная полоска текущего трека с возвратом в Плеер */}
                    {currentTrack && (
                      <div className="flex items-center justify-between gap-3 rounded-2xl border border-white/[0.08] bg-[#131624] px-3.5 py-2.5">
                        <button
                          type="button"
                          onClick={() => setConsoleMode('player')}
                          className="min-w-0 flex-1 text-left cursor-pointer"
                        >
                          <p className="text-xs font-semibold text-[#f5f7ff] truncate">{currentTrack.title}</p>
                          <p className="text-[11px] text-[#77809e] truncate">{currentTrack.artist} · открыть плеер</p>
                        </button>
                        <button
                          type="button"
                          onClick={handleTogglePlay}
                          aria-label={isPlaying ? 'Пауза' : 'Играть'}
                          className="flex h-9 w-9 items-center justify-center rounded-full bg-[#8b5cf6]/25 text-white border border-[#a78bfa]/50 cursor-pointer shrink-0"
                        >
                          {isPlaying ? <Pause className="h-4 w-4" /> : <Play className="h-4 w-4 ml-0.5" />}
                        </button>
                        <button
                          type="button"
                          onClick={() => handleNext(true)}
                          aria-label="Следующий трек"
                          className="flex h-9 w-9 items-center justify-center rounded-full border border-white/10 bg-[#181c2f] text-[#c5cce2] hover:text-white cursor-pointer shrink-0"
                        >
                          <SkipForward className="h-4 w-4" />
                        </button>
                      </div>
                    )}
                  </div>
                </div>
              )}

              <form
                onSubmit={(e) => {
                  e.preventDefault();
                  if (!voiceInput.trim()) return;
                  executeCommand(voiceInput);
                  setVoiceInput('');
                }}
                className="mt-3 flex items-center gap-2"
              >
                <input
                  type="text"
                  value={voiceInput}
                  onChange={(e) => setVoiceInput(e.target.value)}
                  placeholder="Команда: включи Горшка, метлу, Цоя, громче…"
                  className="flex-1 rounded-xl border border-white/[0.08] bg-[#0e101b] px-3.5 py-2.5 text-xs text-[#f5f7ff] placeholder-[#77809e] focus:border-[#8b5cf6] focus:outline-none"
                />
                <button
                  type="submit"
                  className="rounded-xl bg-gradient-to-r from-[#8b5cf6] to-[#4fd1ff] px-4 py-2.5 text-xs font-semibold text-white hover:opacity-95 transition-opacity whitespace-nowrap shrink-0 cursor-pointer"
                >
                  Выполнить
                </button>
              </form>
            </section>

            {/* Правая консоль: EQ Studio / Очередь / Умная волна */}
            <section className="lg:col-span-6 flex flex-col rounded-[28px] border border-white/[0.08] bg-[#10121f]/90 p-6 sm:p-7 shadow-2xl shadow-black/50">
              {/* Переключатель секций студии */}
              <div className="flex items-center justify-between gap-4 border-b border-white/[0.06] pb-4">
                <div className="flex items-center gap-1 rounded-xl bg-[#0b0d17] p-1 border border-white/[0.06]">
                  {(
                    [
                      ['eq', 'EQ Studio', SlidersHorizontal],
                      ['queue', 'Очередь', ListMusic],
                      ['wave', 'Волна', Sparkles],
                    ] as const
                  ).map(([id, label, Icon]) => (
                    <button
                      key={id}
                      type="button"
                      onClick={() => setPanelTab(id)}
                      className={`flex items-center gap-1.5 rounded-lg px-3.5 py-1.5 text-xs font-medium transition-colors whitespace-nowrap shrink-0 cursor-pointer ${
                        panelTab === id
                          ? 'bg-[#8b5cf6]/25 text-white border border-[#8b5cf6]/50'
                          : 'text-[#77809e] hover:text-[#c5cce2] border border-transparent'
                      }`}
                    >
                      <Icon className="h-3.5 w-3.5" />
                      {label}
                    </button>
                  ))}
                </div>

                {panelTab === 'eq' && (
                  <button
                    type="button"
                    role="switch"
                    aria-checked={eqEnabled}
                    aria-label="Включить EQ Studio"
                    onClick={() => handleToggleEqEnabled(!eqEnabled)}
                    className={`relative h-7 w-12 rounded-full border transition-all cursor-pointer ${
                      eqEnabled
                        ? 'border-[#a78bfa]/70 bg-[#8b5cf6] shadow-[0_0_16px_rgba(139,92,246,0.55)]'
                        : 'border-white/10 bg-[#1a1e30]'
                    }`}
                  >
                    <span
                      className={`block h-5 w-5 rounded-full bg-white transition-transform ${
                        eqEnabled ? 'translate-x-6' : 'translate-x-1'
                      }`}
                    />
                  </button>
                )}
              </div>

              {/* Вкладка EQ Studio (1-в-1 в стиле правого экрана макета) */}
              {panelTab === 'eq' && (
                <div className="mt-5 flex flex-1 flex-col justify-between gap-5">
                  <div>
                    <h2 className="text-xl font-bold tracking-tight text-[#f5f7ff]">EQ Studio</h2>
                    <p className="text-xs text-[#77809e]">Тонкая настройка звука в реальном времени</p>
                  </div>

                  {/* 5 вертикальных светящихся фейдеров */}
                  <div
                    className={`grid grid-cols-5 gap-2 rounded-2xl border border-white/[0.06] bg-[#0c0e18] px-3 py-5 transition-opacity ${
                      eqEnabled ? 'opacity-100' : 'opacity-45 pointer-events-none'
                    }`}
                  >
                    {EQ_BAND_LABELS.map((freq, idx) => {
                      const val = eqBands[idx] ?? 0;
                      const pct = ((val + 12) / 24) * 100;
                      return (
                        <div key={freq} className="flex flex-col items-center gap-3">
                          <span className="font-mono-tabular text-[11px] text-[#77809e]">{freq}</span>
                          <div className="relative flex h-44 w-10 items-center justify-center">
                            {/* Неоновая вертикальная дорожка */}
                            <div className="relative h-40 w-1.5 rounded-full bg-[#151828] overflow-hidden">
                              <div
                                className="absolute bottom-0 left-0 right-0 rounded-full bg-gradient-to-t from-[#8b5cf6]/30 via-[#8b5cf6] to-[#4fd1ff]"
                                style={{ height: `${pct}%` }}
                              />
                            </div>
                            {/* Визуальная капсула ползунка */}
                            <div
                              className="pointer-events-none absolute left-1/2 -translate-x-1/2 h-3.5 w-7 rounded-full border border-white/15 bg-[#1f243b] shadow-[0_0_16px_rgba(139,92,246,0.55)] flex items-center justify-center"
                              style={{ bottom: `calc(${pct}% * 0.88 + 4px)` }}
                            >
                              <span className="h-0.5 w-3.5 rounded-full bg-gradient-to-r from-[#a78bfa] to-[#4fd1ff]" />
                            </div>
                            {/* Нативный вертикальный range поверх */}
                            <input
                              type="range"
                              min={-12}
                              max={12}
                              step={0.5}
                              value={val}
                              onChange={(e) => handleChangeEqBand(idx, Number(e.target.value))}
                              aria-label={`Полоса ${freq}`}
                              className="absolute h-40 w-8 opacity-0 cursor-pointer [writing-mode:vertical-lr] [direction:rtl]"
                            />
                          </div>
                          <span className="font-mono-tabular text-[11px] font-medium text-[#c5cce2]">
                            {val > 0 ? `+${val.toFixed(1)}` : val.toFixed(1)}
                          </span>
                        </div>
                      );
                    })}
                  </div>

                  {/* Пресеты */}
                  <div>
                    <div className="mb-2 flex items-center justify-between text-xs">
                      <span className="font-medium text-[#c5cce2]">Пресеты</span>
                      <span className="text-[#77809e]">{eqIndex < 0 ? 'Свой' : EQ_PRESETS[eqIndex]?.label}</span>
                    </div>
                    <div className="flex flex-wrap gap-2">
                      {EQ_PRESETS.map((preset, idx) => (
                        <button
                          key={preset.id}
                          type="button"
                          onClick={() => handleSelectEq(idx)}
                          className={`rounded-xl border px-3.5 py-2 text-xs font-medium transition-colors whitespace-nowrap shrink-0 cursor-pointer ${
                            eqIndex === idx
                              ? 'border-[#a78bfa] bg-[#8b5cf6]/20 text-white'
                              : 'border-white/[0.08] bg-[#131624] text-[#77809e] hover:text-white'
                          }`}
                        >
                          {preset.label}
                        </button>
                      ))}
                    </div>
                  </div>

                  {/* Карточка Bass Boost */}
                  <div className="flex items-center justify-between gap-4 rounded-2xl border border-white/[0.08] bg-[#131624] px-4 py-3.5">
                    <div className="flex items-center gap-3">
                      <div
                        className={`flex h-10 w-10 items-center justify-center rounded-full border ${
                          bassBoost
                            ? 'border-[#a78bfa]/60 bg-[#8b5cf6]/25 text-[#4fd1ff]'
                            : 'border-white/10 bg-[#1a1e30] text-[#77809e]'
                        }`}
                      >
                        <SlidersHorizontal className="h-4 w-4" />
                      </div>
                      <div>
                        <p className="text-sm font-semibold text-[#f5f7ff]">Bass Boost</p>
                        <p className="text-xs text-[#77809e]">Усиление глубины низких частот</p>
                      </div>
                    </div>
                    <button
                      type="button"
                      role="switch"
                      aria-checked={bassBoost}
                      aria-label="Bass Boost"
                      onClick={() => handleToggleBassBoost(!bassBoost)}
                      className={`relative h-7 w-12 rounded-full border transition-all cursor-pointer ${
                        bassBoost
                          ? 'border-[#a78bfa]/70 bg-[#8b5cf6] shadow-[0_0_16px_rgba(139,92,246,0.55)]'
                          : 'border-white/10 bg-[#1a1e30]'
                      }`}
                    >
                      <span
                        className={`block h-5 w-5 rounded-full bg-white transition-transform ${
                          bassBoost ? 'translate-x-6' : 'translate-x-1'
                        }`}
                      />
                    </button>
                  </div>

                  {/* Баланс L / R + Громкость и таймер сна */}
                  <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
                    <div className="rounded-2xl border border-white/[0.08] bg-[#131624] px-4 py-3">
                      <div className="mb-1.5 flex items-center justify-between text-xs text-[#77809e]">
                        <span>Баланс</span>
                        <button
                          type="button"
                          onClick={() => handleBalanceChange(0)}
                          className="font-mono-tabular text-[#4fd1ff] hover:underline cursor-pointer"
                        >
                          {balLabel}
                        </button>
                      </div>
                      <div className="flex items-center gap-2">
                        <span className="text-[11px] font-semibold text-[#77809e]">L</span>
                        <input
                          type="range"
                          min={-1}
                          max={1}
                          step={0.05}
                          value={stereoBalance}
                          onChange={(e) => handleBalanceChange(Number(e.target.value))}
                          aria-label="Стереобаланс"
                          className="flex-1 h-1.5 accent-[#8b5cf6] cursor-pointer"
                        />
                        <span className="text-[11px] font-semibold text-[#77809e]">R</span>
                      </div>
                    </div>

                    <div className="rounded-2xl border border-white/[0.08] bg-[#131624] px-4 py-3">
                      <div className="mb-1.5 flex items-center justify-between text-xs text-[#77809e]">
                        <span className="flex items-center gap-1">
                          <Moon className="h-3.5 w-3.5 text-[#a78bfa]" />
                          Таймер сна
                        </span>
                        <span className="font-mono-tabular text-[#c5cce2]">
                          {sleepMinutes ? `${sleepMinutes} мин` : 'Выкл'}
                        </span>
                      </div>
                      <div className="grid grid-cols-4 gap-1.5">
                        {[null, 15, 30, 60].map((m) => (
                          <button
                            key={String(m)}
                            type="button"
                            onClick={() => setSleepMinutes(m)}
                            className={`rounded-lg py-1 text-[11px] font-medium transition-colors cursor-pointer ${
                              sleepMinutes === m
                                ? 'bg-[#8b5cf6]/30 text-white border border-[#a78bfa]/60'
                                : 'bg-[#0e101b] text-[#77809e] hover:text-white border border-transparent'
                            }`}
                          >
                            {m === null ? 'Выкл' : `${m}м`}
                          </button>
                        ))}
                      </div>
                    </div>
                  </div>
                </div>
              )}

              {/* Вкладка Очередь */}
              {panelTab === 'queue' && (
                <div className="mt-4 flex flex-1 flex-col gap-3">
                  <div className="flex items-center justify-between">
                    <p className="text-xs text-[#77809e]">
                      {modeLabel} · {queue.length} треков
                    </p>
                    <button
                      type="button"
                      onClick={() => startQueue(buildSmartWave(), 'Моя волна')}
                      className="text-xs font-medium text-[#a78bfa] hover:text-white cursor-pointer"
                    >
                      Обновить волну
                    </button>
                  </div>

                  <div className="flex-1 max-h-[440px] space-y-1 overflow-y-auto pr-1">
                    {queue.map((track, idx) => {
                      const active = idx === queueIdx;
                      const liked = likedIds.has(track.id);
                      return (
                        <div
                          key={`${track.id}-${idx}`}
                          onClick={() => startQueue(queue, modeLabel, idx, true)}
                          className={`flex items-center gap-3 rounded-xl px-3 py-2.5 transition-colors cursor-pointer ${
                            active
                              ? 'bg-[#8b5cf6]/18 border border-[#8b5cf6]/35'
                              : 'border border-transparent hover:bg-white/[0.04]'
                          }`}
                        >
                          <span
                            className={`w-6 text-center font-mono-tabular text-xs ${
                              active ? 'text-[#4fd1ff] font-semibold' : 'text-[#77809e]'
                            }`}
                          >
                            {idx + 1}
                          </span>
                          <div className="min-w-0 flex-1">
                            <p className={`text-sm truncate ${active ? 'text-[#4fd1ff] font-semibold' : 'text-[#f5f7ff]'}`}>
                              {track.title}
                            </p>
                            <p className="text-xs text-[#77809e] truncate">{track.artist}</p>
                          </div>
                          <span className="font-mono-tabular text-xs text-[#77809e]">{formatClock(track.durationSec)}</span>
                          <button
                            type="button"
                            onClick={(e) => {
                              e.stopPropagation();
                              handleToggleLikeTrack(track);
                            }}
                            aria-label="Лайк"
                            className="p-1 text-[#77809e] hover:text-rose-400 cursor-pointer"
                          >
                            <Heart className={`h-4 w-4 ${liked ? 'fill-rose-400 text-rose-400' : ''}`} />
                          </button>
                        </div>
                      );
                    })}
                  </div>
                </div>
              )}

              {/* Вкладка Умная волна */}
              {panelTab === 'wave' && (
                <div className="mt-5 flex flex-1 flex-col justify-between gap-5">
                  <div>
                    <h2 className="text-lg font-bold text-[#f5f7ff]">Умная оффлайн-волна</h2>
                    <p className="mt-1 text-xs text-[#77809e]">
                      Очередь собирается локально из вашей медиатеки по лайкам, весам артистов и правилу 3 пропусков подряд.
                    </p>
                  </div>

                  <div className="grid grid-cols-2 gap-2.5">
                    {[
                      ['Моя волна', () => startQueue(buildSmartWave(), 'Моя волна')],
                      ['Русская волна', () => startQueue(buildSmartWave('russian'), 'Русская волна')],
                      ['Зарубежная', () => startQueue(buildSmartWave('not-russian'), 'Зарубежная волна')],
                      ['Мои лайки', () => executeCommand('включи мои лайки')],
                      ['Бодрое', () => executeCommand('включи бодрое')],
                      ['Спокойное', () => executeCommand('включи спокойное')],
                    ].map(([label, handler]) => (
                      <button
                        key={label as string}
                        type="button"
                        onClick={handler as () => void}
                        className="rounded-2xl border border-white/[0.08] bg-[#131624] px-4 py-3.5 text-left text-sm font-medium text-[#f5f7ff] hover:border-[#8b5cf6]/50 hover:bg-[#181c2f] transition-colors cursor-pointer"
                      >
                        {label as string}
                      </button>
                    ))}
                  </div>

                  <div className="rounded-2xl border border-white/[0.08] bg-[#131624] p-4">
                    <div className="mb-2 flex items-center justify-between text-xs text-[#77809e]">
                      <span>Громкость плеера</span>
                      <span className="font-mono-tabular text-[#f5f7ff]">{volumePct}%</span>
                    </div>
                    <input
                      type="range"
                      min={0}
                      max={100}
                      value={volumePct}
                      onChange={(e) => {
                        const v = Number(e.target.value);
                        setVolumePct(v);
                        audioEngine.setVolumePercent(v);
                      }}
                      aria-label="Громкость"
                      className="w-full h-1.5 accent-[#8b5cf6] cursor-pointer"
                    />
                  </div>
                </div>
              )}
            </section>
          </div>
        )}

        {/* Вкладка Медиатека */}
        {activeTab === 'library' && (
          <section className="rounded-[28px] border border-white/[0.08] bg-[#10121f]/90 p-6 shadow-2xl shadow-black/50 space-y-5">
            <div className="flex flex-wrap items-center justify-between gap-4">
              <div>
                <h1 className="text-2xl font-bold text-[#f5f7ff]">Медиатека</h1>
                <p className="text-xs text-[#77809e]">
                  {library.length} треков · {likedIds.size} любимых
                </p>
              </div>
              <div className="flex flex-wrap items-center gap-2">
                <div className="relative">
                  <Search className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-[#77809e]" />
                  <input
                    type="text"
                    value={librarySearch}
                    onChange={(e) => setLibrarySearch(e.target.value)}
                    placeholder="Поиск трека или артиста…"
                    className="w-60 rounded-xl border border-white/[0.08] bg-[#0c0e18] pl-9 pr-3 py-2 text-xs text-[#f5f7ff] placeholder-[#77809e] focus:border-[#8b5cf6] focus:outline-none"
                  />
                </div>
                <div className="flex items-center gap-1 rounded-xl bg-[#0c0e18] p-1 border border-white/[0.06]">
                  {(
                    [
                      ['all', 'Все'],
                      ['liked', 'Любимые'],
                      ['artists', 'Артисты'],
                    ] as const
                  ).map(([id, label]) => (
                    <button
                      key={id}
                      type="button"
                      onClick={() => setLibraryFilter(id)}
                      className={`rounded-lg px-3 py-1.5 text-xs font-medium transition-colors cursor-pointer ${
                        libraryFilter === id ? 'bg-[#8b5cf6]/25 text-white' : 'text-[#77809e] hover:text-white'
                      }`}
                    >
                      {label}
                    </button>
                  ))}
                </div>
              </div>
            </div>

            {libraryFilter === 'artists' ? (
              <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3">
                {artistsGrouped.map(([artist, tracks]) => (
                  <button
                    key={artist}
                    type="button"
                    onClick={() => {
                      startQueue(tracks, artist, 0);
                      setActiveTab('player');
                    }}
                    className="flex items-center gap-3 rounded-2xl border border-white/[0.08] bg-[#131624] p-4 text-left hover:border-[#8b5cf6]/50 transition-colors cursor-pointer"
                  >
                    <div className="flex h-11 w-11 items-center justify-center rounded-full bg-[#8b5cf6]/20 text-[#a78bfa]">
                      <UserRound className="h-5 w-5" />
                    </div>
                    <div className="min-w-0 flex-1">
                      <p className="text-sm font-semibold text-[#f5f7ff] truncate">{artist}</p>
                      <p className="text-xs text-[#77809e]">{tracks.length} треков</p>
                    </div>
                    <Play className="h-4 w-4 text-[#77809e]" />
                  </button>
                ))}
              </div>
            ) : (
              <div className="divide-y divide-white/[0.05]">
                {filteredLibrary.map((track, idx) => {
                  const isCurrent = currentTrack?.id === track.id;
                  const liked = likedIds.has(track.id);
                  return (
                    <div
                      key={track.id}
                      onClick={() => {
                        startQueue(filteredLibrary, libraryFilter === 'liked' ? 'Мои лайки' : 'Медиатека', idx);
                        setActiveTab('player');
                      }}
                      className="flex items-center gap-3 py-3 px-2 rounded-xl hover:bg-white/[0.03] transition-colors cursor-pointer"
                    >
                      <div
                        className={`flex h-10 w-10 items-center justify-center rounded-xl ${
                          isCurrent ? 'bg-[#8b5cf6]/25 text-[#4fd1ff]' : 'bg-[#141726] text-[#77809e]'
                        }`}
                      >
                        <Music2 className="h-4 w-4" />
                      </div>
                      <div className="min-w-0 flex-1">
                        <p className={`text-sm font-medium truncate ${isCurrent ? 'text-[#4fd1ff]' : 'text-[#f5f7ff]'}`}>
                          {track.title}
                        </p>
                        <p className="text-xs text-[#77809e] truncate">
                          {track.artist} · {track.album}
                        </p>
                      </div>
                      <span className="font-mono-tabular text-xs text-[#77809e]">{formatClock(track.durationSec)}</span>
                      <button
                        type="button"
                        onClick={(e) => {
                          e.stopPropagation();
                          handleToggleLikeTrack(track);
                        }}
                        aria-label="Лайк"
                        className="p-2 text-[#77809e] hover:text-rose-400 cursor-pointer"
                      >
                        <Heart className={`h-4 w-4 ${liked ? 'fill-rose-400 text-rose-400' : ''}`} />
                      </button>
                    </div>
                  );
                })}
              </div>
            )}
          </section>
        )}

        {/* Вкладка Android APK */}
        {activeTab === 'apk' && (
          <section className="rounded-[28px] border border-white/[0.08] bg-[#10121f]/90 p-6 sm:p-8 shadow-2xl shadow-black/50 space-y-6">
            <div>
              <h1 className="text-2xl font-bold text-[#f5f7ff]">Нативный Android APK (100% оффлайн)</h1>
              <p className="mt-1 text-sm text-[#77809e]">
                Автоматическая сборка через GitHub Actions с вшитой русской оффлайн-моделью Vosk и без разрешения INTERNET.
              </p>
            </div>

            <div className="grid grid-cols-1 gap-4 md:grid-cols-3">
              <div className="rounded-2xl border border-white/[0.08] bg-[#131624] p-5">
                <h2 className="text-sm font-semibold text-[#f5f7ff]">01. Плеер и EQ Studio</h2>
                <p className="mt-2 text-xs leading-relaxed text-[#77809e]">
                  Media3 ExoPlayer, круговой циферблат с прогрессом, 64-полосная волна, 5-полосный аппаратный эквалайзер и Bass Boost.
                </p>
              </div>
              <div className="rounded-2xl border border-white/[0.08] bg-[#131624] p-5">
                <h2 className="text-sm font-semibold text-[#f5f7ff]">02. Оффлайн-голос Vosk + TTS</h2>
                <p className="mt-2 text-xs leading-relaxed text-[#77809e]">
                  Модель vosk-model-small-ru-0.22 вшивается в APK при сборке в GitHub Actions и распаковывается в фоне на первом запуске.
                </p>
              </div>
              <div className="rounded-2xl border border-white/[0.08] bg-[#131624] p-5">
                <h2 className="text-sm font-semibold text-[#f5f7ff]">03. Локальная медиатека</h2>
                <p className="mt-2 text-xs leading-relaxed text-[#77809e]">
                  Авто-сканирование Android MediaStore + ручной выбор любых папок с музыкой (SAF) и разбор имён «Артист - Трек».
                </p>
              </div>
            </div>
          </section>
        )}
      </main>
    </div>
  );
}
export default App;
