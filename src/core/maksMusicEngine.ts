import musicWordsRaw from '../../android/app/src/main/assets/config/music_words.json';
import artistAliasesRaw from '../../android/app/src/main/assets/config/aliases.json';
import genreAliasesRaw from '../../android/app/src/main/assets/config/genre_aliases.json';
import genreTagsRaw from '../../android/app/src/main/assets/config/genre_tags.json';
import moodAliasesRaw from '../../android/app/src/main/assets/config/mood_aliases.json';
import searchNoiseRaw from '../../android/app/src/main/assets/config/search_noise.json';
import intentRoutesRaw from '../../android/app/src/main/assets/config/intent_routes_music.json';

import musicPlayRaw from '../../android/app/src/main/assets/responses/music_play.json';
import appraisalRaw from '../../android/app/src/main/assets/responses/appraisal.json';
import wakeRaw from '../../android/app/src/main/assets/responses/wake.json';
import smalltalkRaw from '../../android/app/src/main/assets/responses/smalltalk.json';
import errorsRaw from '../../android/app/src/main/assets/responses/errors.json';

export type MusicIntent =
  | 'MUSIC_PLAY'
  | 'MUSIC_WAVE'
  | 'MUSIC_LIKE'
  | 'MUSIC_DISLIKE'
  | 'MUSIC_LOCAL_LIKED'
  | 'MUSIC_PAUSE'
  | 'MUSIC_STOP'
  | 'MUSIC_RESUME'
  | 'MUSIC_NEXT'
  | 'MUSIC_PREV'
  | 'MUSIC_NOW_PLAYING'
  | 'VOLUME_UP'
  | 'VOLUME_DOWN'
  | 'VOLUME_SET'
  | 'SMALLTALK'
  | 'UNKNOWN';

export interface LocalTrackItem {
  id: string;
  artist: string;
  title: string;
  album: string;
  genre: string;
  durationSec: number;
  isRussian: boolean;
  objectUrl?: string;
  synthRootHz?: number;
  synthMood?: 'rock' | 'calm' | 'upbeat' | 'dark' | 'classic';
}

export interface ParsedMusicDecision {
  intent: MusicIntent;
  action: string;
  entityType?: string;
  entityValue?: string;
  language?: 'russian' | 'not-russian';
  volumeLevel?: number;
  normalizedText: string;
  confidence: number;
}

export interface PhraseOption {
  text: string;
  style?: string;
  weight?: number;
  night?: boolean;
  emotion?: string;
  rate?: string;
  pitch?: string;
}

export interface MaksVoiceReply {
  displayText: string;
  emotion: string;
  speechRate: number;
  speechPitch: number;
}

const VOWELS = 'аеёиоуыэюя';
const VOWEL_MAP: Record<string, string> = { я: 'а', е: 'э', ю: 'у', и: 'и' };

const genreAliases = genreAliasesRaw as Record<string, string>;
const genreTags = genreTagsRaw as Record<string, string[]>;
const moodAliases = moodAliasesRaw as Record<string, string>;
const artistAliases = new Map<string, string>();
for (const [k, v] of Object.entries(
  artistAliasesRaw as Record<string, string | { artist_name?: string }>,
)) {
  const key = k.toLowerCase().trim();
  const canonical = typeof v === 'string' ? v.trim() : (v?.artist_name ?? '').trim();
  if (key && canonical) {
    artistAliases.set(key, canonical);
  }
}
export const BUNDLED_ARTIST_ALIAS_COUNT = artistAliases.size;
export const BUNDLED_GENRE_ALIAS_COUNT = Object.keys(genreAliases).length;
const searchNoise = new Set<string>(
  ((searchNoiseRaw as { noise_words?: string[] }).noise_words ?? []).map((w) => w.toLowerCase()),
);
const musicWords = musicWordsRaw as {
  stop_prefixes?: string[];
  play_triggers?: string[];
  noise_words?: string[];
  artist_hints?: string[];
  track_hints?: string[];
  number_words?: Record<string, number>;
};

const STOP_PREFIXES = [...(musicWords.stop_prefixes ?? ['макс', 'макса', 'максу'])].sort(
  (a, b) => b.length - a.length,
);
const PLAY_TRIGGERS = new Set([
  ...(musicWords.play_triggers ?? ['включи', 'поставь', 'сыграй', 'вруби']),
  'навали',
  'зажги',
]);
const NOISE_WORDS = new Set([
  ...(musicWords.noise_words ?? []),
  'хочу',
  'послушать',
  'послушаем',
]);
const ARTIST_HINTS = new Set(musicWords.artist_hints ?? []);
const TRACK_HINTS = new Set(musicWords.track_hints ?? []);
const GENERIC_MUSIC_WORDS = new Set([
  'музыку', 'музыка', 'музыки', 'музыке', 'песни', 'песню', 'песен', 'треки', 'треков',
]);

const LANG_MODIFIERS: Record<string, 'russian' | 'not-russian'> = {
  русская: 'russian', русскую: 'russian', русский: 'russian', русские: 'russian',
  русского: 'russian', русской: 'russian', отечественная: 'russian', нашу: 'russian',
  зарубежная: 'not-russian', зарубежную: 'not-russian', зарубежный: 'not-russian',
  иностранная: 'not-russian', иностранную: 'not-russian', английская: 'not-russian',
};

const NUMBER_WORDS: Record<string, number> = {
  ...(musicWords.number_words ?? {}),
  один: 1, два: 2, три: 3, четыре: 4, пять: 5, шесть: 6, семь: 7, восемь: 8, девять: 9,
  пятнадцать: 15, максимум: 100, минимум: 10,
};

const exactIntentMap = new Map<string, MusicIntent>();
const allRouteEntries: Array<[string, MusicIntent]> = [];
for (const [intentName, phrases] of Object.entries(intentRoutesRaw as Record<string, string[]>)) {
  const intent = intentName as MusicIntent;
  for (const phrase of phrases) {
    const p = phrase.toLowerCase().trim();
    if (!p) continue;
    if (!exactIntentMap.has(p)) exactIntentMap.set(p, intent);
    allRouteEntries.push([p, intent]);
  }
}

const allResponses: Record<string, PhraseOption[]> = {};
for (const bundle of [musicPlayRaw, appraisalRaw, wakeRaw, smalltalkRaw, errorsRaw]) {
  for (const [key, list] of Object.entries(bundle as Record<string, unknown>)) {
    if (!Array.isArray(list)) continue;
    allResponses[key] = list.map((item) =>
      typeof item === 'string' ? { text: item } : (item as PhraseOption),
    );
  }
}

/** Точный порт workers/music/phon.py */
export function phon(input: string): string {
  let w = input.toLowerCase().replace(/ё/g, 'е');
  w = w.replace(/[ьъ]([яеюи])/g, (_, g1: string) => 'й' + (VOWEL_MAP[g1] ?? g1));
  w = w.replace(/и([яеюё])/g, (_, g1: string) => 'й' + (VOWEL_MAP[g1] ?? g1));
  w = w.replace(new RegExp(`([${VOWELS}])и([${VOWELS}])`, 'g'), '$1й$2');
  w = w.replace(new RegExp(`(^|[${VOWELS}])я`, 'g'), '$1йа');
  w = w.replace(new RegExp(`(^|[${VOWELS}])ю`, 'g'), '$1йу');
  w = w.replace(/[ьъ]/g, '');
  return w.replace(/й+/g, 'й');
}

export function ratio(a: string, b: string): number {
  if (a === b) return 1;
  if (!a || !b) return 0;
  const m = a.length;
  const n = b.length;
  let prev = Array.from({ length: n + 1 }, (_, i) => i);
  let curr = new Array<number>(n + 1);
  for (let i = 1; i <= m; i++) {
    curr[0] = i;
    for (let j = 1; j <= n; j++) {
      const cost = a[i - 1] === b[j - 1] ? 0 : 1;
      curr[j] = Math.min(curr[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost);
    }
    const tmp = prev;
    prev = curr;
    curr = tmp;
  }
  return Math.max(0, 1 - (2 * prev[n]) / (m + n));
}

export function cleanSpeechText(raw: string): string {
  if (!raw.trim()) return '';
  let t = raw.replace(/\+([аеёиоуыэюяАЕЁИОУЫЭЮЯ])/g, '$1');
  t = t.replace(/sil<\[\s*(\d+)\s*]>/g, (_, msStr: string) =>
    Number(msStr) >= 150 ? ', ' : ' ',
  );
  t = t.trim().replace(/^[,;:]+/, '').trim();
  t = t.replace(/\s+([,.!?;:])/g, '$1');
  t = t.replace(/([,.!?])\s*[,.]+/g, '$1');
  return t.replace(/\s+/g, ' ').trim();
}

export function normalizeText(raw: string): string {
  let q = raw.toLowerCase().trim();
  let changed = true;
  while (changed) {
    changed = false;
    for (const prefix of STOP_PREFIXES) {
      if (q === prefix || (q.startsWith(prefix) && !/[а-яёa-z0-9]/i.test(q[prefix.length] ?? ''))) {
        q = q.slice(prefix.length).replace(/^[\s,.!?:;-]+/, '');
        changed = true;
        break;
      }
    }
  }
  q = q.replace(/[^\p{L}\p{N}\s]/gu, ' ');
  q = q.replace(/(^|\s)(пожалуйста|будь добр|будь добра|макс|эй|слушай|ну|вот|как бы|типа|давай)(?=\s|$)/gi, ' ');
  return q.replace(/\s+/g, ' ').trim();
}

export function parseVolume(text: string): number | null {
  const lower = text.toLowerCase();
  if (!/(громкост|звук|процент)/.test(lower)) return null;
  const digit = lower.match(/\b(\d{1,3})\b/);
  if (digit) return Math.min(100, Math.max(0, Number(digit[1])));
  let sum = 0;
  let found = false;
  for (const tok of lower.split(/\s+/)) {
    if (tok in NUMBER_WORDS) {
      sum += NUMBER_WORDS[tok];
      found = true;
    }
  }
  return found ? Math.min(100, Math.max(0, sum)) : null;
}

function russianCaseVariants(word: string): string[] {
  if (word.length < 4) return [];
  const out: string[] = [];
  if (word.endsWith('ю')) {
    out.push(word.slice(0, -1) + 'я', word.slice(0, -1) + 'а');
  } else if (word.endsWith('у')) {
    out.push(word.slice(0, -1) + 'а', word.slice(0, -1));
    if (word.length >= 5 && word.endsWith('ку')) {
      out.push(word.slice(0, -2) + 'ок');
    }
  } else if (word.endsWith('а')) {
    out.push(word.slice(0, -1));
    if (word.length >= 5 && word.endsWith('ка')) {
      out.push(word.slice(0, -2) + 'ок');
    }
  } else if (word.endsWith('я')) {
    out.push(word.slice(0, -1) + 'й');
  }
  return out;
}

export function resolveMusicCommand(raw: string, library: LocalTrackItem[]): ParsedMusicDecision {
  const normalized = normalizeText(raw);
  if (!normalized) {
    return { intent: 'UNKNOWN', action: 'fallback', normalizedText: '', confidence: 0 };
  }

  let intent: MusicIntent = 'UNKNOWN';
  let confidence = 0;

  const exact = exactIntentMap.get(normalized);
  if (exact) {
    intent = exact;
    confidence = 1.0;
  } else if (parseVolume(raw) !== null) {
    intent = 'VOLUME_SET';
    confidence = 0.96;
  } else if (['мои лайки', 'мою музыку', 'моя музыка', 'любимое', 'любимые', 'избранное'].some((k) => normalized.includes(k))) {
    intent = 'MUSIC_LOCAL_LIKED';
    confidence = 0.95;
  } else if (normalized.includes('не нравится') || normalized.includes('дизлайк') || normalized.includes('убери трек')) {
    intent = 'MUSIC_DISLIKE';
    confidence = 0.95;
  } else if (normalized.includes('нравится') || normalized === 'лайк' || normalized.includes('лайкни')) {
    intent = 'MUSIC_LIKE';
    confidence = 0.95;
  } else if (normalized.includes('волн')) {
    intent = 'MUSIC_WAVE';
    confidence = 0.95;
  } else if (PLAY_TRIGGERS.has(normalized.split(' ')[0])) {
    intent = 'MUSIC_PLAY';
    confidence = 0.92;
  } else {
    let bestScore = 0;
    let bestIntent: MusicIntent = 'UNKNOWN';
    for (const [phrase, candIntent] of allRouteEntries) {
      const s = ratio(normalized, phrase);
      if (s > bestScore) {
        bestScore = s;
        bestIntent = candIntent;
      }
    }
    if (bestScore >= 0.8) {
      intent = bestIntent;
      confidence = bestScore;
    }
  }

  const controlActions: Partial<Record<MusicIntent, string>> = {
    VOLUME_UP: 'volume_up',
    VOLUME_DOWN: 'volume_down',
    VOLUME_SET: 'volume_set',
    MUSIC_PAUSE: 'pause',
    MUSIC_STOP: 'stop',
    MUSIC_RESUME: 'resume',
    MUSIC_NEXT: 'next',
    MUSIC_PREV: 'prev',
    MUSIC_NOW_PLAYING: 'now_playing',
    MUSIC_LIKE: 'like_current',
    MUSIC_DISLIKE: 'dislike_current',
    MUSIC_LOCAL_LIKED: 'play_local_liked',
    SMALLTALK: 'smalltalk',
  };

  if (controlActions[intent]) {
    return {
      intent,
      action: controlActions[intent]!,
      volumeLevel: intent === 'VOLUME_SET' ? (parseVolume(raw) ?? undefined) : undefined,
      normalizedText: normalized,
      confidence,
    };
  }

  // Извлечение языка
  const tokens = normalized.split(' ').filter(Boolean);
  let language: 'russian' | 'not-russian' | undefined;
  for (const tok of tokens) {
    if (tok in LANG_MODIFIERS) language = LANG_MODIFIERS[tok];
  }

  if (intent === 'MUSIC_WAVE') {
    return {
      intent,
      action: 'play_wave',
      entityType: language ? 'wave_lang' : 'wave',
      entityValue: language,
      language,
      normalizedText: normalized,
      confidence,
    };
  }

  if (intent === 'MUSIC_PLAY') {
    const filtered: string[] = [];
    for (let i = 0; i < tokens.length; i++) {
      if ((tokens[i] === 'что' || tokens[i] === 'чего') && tokens[i + 1] === 'нибудь') {
        i++;
        continue;
      }
      const w = tokens[i];
      if (PLAY_TRIGGERS.has(w) || NOISE_WORDS.has(w) || ARTIST_HINTS.has(w) || TRACK_HINTS.has(w)) {
        continue;
      }
      filtered.push(w);
    }

    const coreWords = filtered.filter((w) => !GENERIC_MUSIC_WORDS.has(w) && !(w in LANG_MODIFIERS));
    if (coreWords.length === 0) {
      return {
        intent,
        action: 'play_wave',
        entityType: language ? 'wave_lang' : 'wave',
        entityValue: language,
        language,
        normalizedText: normalized,
        confidence,
      };
    }

    const candidate = filtered.filter((w) => !GENERIC_MUSIC_WORDS.has(w)).join(' ').trim();
    const candPhon = phon(candidate);

    if (moodAliases[candidate]) {
      return {
        intent,
        action: 'play_mood',
        entityType: 'mood',
        entityValue: moodAliases[candidate],
        language,
        normalizedText: normalized,
        confidence: 1.0,
      };
    }

    if (genreAliases[candidate]) {
      return {
        intent,
        action: 'play_genre',
        entityType: 'genre',
        entityValue: genreAliases[candidate],
        language,
        normalizedText: normalized,
        confidence: 1.0,
      };
    }

    // Локальные артисты (точный → phon → русские падежи → fuzzy)
    const artistMap = new Map<string, string>();
    for (const t of library) {
      if (t.artist) {
        artistMap.set(t.artist.toLowerCase(), t.artist);
        artistMap.set(phon(t.artist), t.artist);
      }
    }

    const directArtist = artistMap.get(candidate) ?? artistMap.get(candPhon);
    if (directArtist) {
      return {
        intent,
        action: 'play_artist',
        entityType: 'artist',
        entityValue: directArtist,
        language,
        normalizedText: normalized,
        confidence: 0.98,
      };
    }

    for (const variant of russianCaseVariants(candidate)) {
      const hit = artistMap.get(variant) ?? artistMap.get(phon(variant));
      if (hit) {
        return {
          intent,
          action: 'play_artist',
          entityType: 'artist',
          entityValue: hit,
          language,
          normalizedText: normalized,
          confidence: 0.94,
        };
      }
    }

    // Вшитый словарь алиасов артистов (aliases.json)
    const resolveCanonical = (canon: string): string =>
      artistMap.get(canon.toLowerCase()) ?? artistMap.get(phon(canon)) ?? canon;

    const directAlias = artistAliases.get(candidate);
    if (directAlias) {
      return {
        intent,
        action: 'play_artist',
        entityType: 'artist',
        entityValue: resolveCanonical(directAlias),
        language,
        normalizedText: normalized,
        confidence: 0.98,
      };
    }

    for (const [aliasKey, canonical] of artistAliases.entries()) {
      if (phon(aliasKey) === candPhon) {
        return {
          intent,
          action: 'play_artist',
          entityType: 'artist',
          entityValue: resolveCanonical(canonical),
          language,
          normalizedText: normalized,
          confidence: 0.95,
        };
      }
    }

    for (const variant of russianCaseVariants(candidate)) {
      const hitAlias = artistAliases.get(variant);
      if (hitAlias) {
        return {
          intent,
          action: 'play_artist',
          entityType: 'artist',
          entityValue: resolveCanonical(hitAlias),
          language,
          normalizedText: normalized,
          confidence: 0.93,
        };
      }
    }

    // Поиск по локальной библиотеке (UNRESOLVED → play_search)
    const searchQuery = normalized
      .split(' ')
      .filter((w) => w && !searchNoise.has(w))
      .join(' ')
      .trim() || candidate;

    return {
      intent,
      action: 'play_search',
      entityType: 'search',
      entityValue: searchQuery,
      language,
      normalizedText: normalized,
      confidence: 0.82,
    };
  }

  return { intent: 'UNKNOWN', action: 'fallback', normalizedText: normalized, confidence };
}

export function matchGenreTracks(genreId: string, library: LocalTrackItem[]): LocalTrackItem[] {
  const clean = genreId.toLowerCase().replace(/^genre:/, '');
  const hints = new Set<string>([clean, ...(genreTags[clean] ?? [])]);
  for (const [alias, tags] of Object.entries(genreTags)) {
    if (alias === clean || tags.includes(clean)) {
      tags.forEach((t) => hints.add(t));
    }
  }
  const matched = library.filter((t) =>
    Array.from(hints).some((h) => t.genre.toLowerCase().includes(h)),
  );
  if (matched.length > 0) return matched;
  if (clean.startsWith('rus') || clean === 'shanson') {
    return library.filter((t) => t.isRussian);
  }
  return [];
}

export function matchMoodTracks(moodName: string, library: LocalTrackItem[]): LocalTrackItem[] {
  const m = moodName.toLowerCase();
  const hints =
    m.includes('энерг') || m.includes('бодр') || m.includes('агресс')
      ? ['rock', 'metal', 'punk', 'dnb', 'dance']
      : m.includes('спокой') || m.includes('расслаб') || m.includes('мечтат')
        ? ['classical', 'jazz', 'ambient', 'indie']
        : m.includes('груст') || m.includes('мрач')
          ? ['blues', 'post', 'indie', 'rock']
          : ['pop', 'dance', 'funk'];

  const matched = library.filter((t) => hints.some((h) => t.genre.toLowerCase().includes(h)));
  return matched.length > 0 ? matched : library;
}

const DEFAULT_FALLBACKS: Record<string, string> = {
  PLAY_WAVE: 'Включаю вашу локальную волну',
  PLAY_ARTIST: 'Включаю {artist}',
  PLAY_GENRE: 'Включаю {genre}',
  PLAY_MOOD: 'Включаю {mood}',
  PLAY_LOCAL_LIKED: 'Включаю ваши любимые треки',
  PLAY_SEARCH_FOUND: 'Нашёл: {artist} — {title}',
  PLAY_SEARCH_NOT_FOUND: 'В локальной медиатеке не нашёл: {query}',
  LIKE_ADDED: 'Сохранил в любимые треки',
  DISLIKE_ADDED: 'Убрал, больше не буду предлагать',
  NEXT_TRACK: 'Дальше',
  PREV_TRACK: 'Предыдущий трек',
  PAUSE: 'Пауза',
  RESUME: 'Продолжаю',
  STOP: 'Остановил воспроизведение',
  VOLUME_UP: 'Сделал громче',
  VOLUME_DOWN: 'Сделал тише',
  VOLUME_SET: 'Громкость {level} процентов',
  NOW_PLAYING: 'Сейчас играет {artist} — {title}',
  NO_TRACK_PLAYING: 'Сейчас ничего не играет',
  CANCELLED: 'Хорошо, оставляю в волне',
  NOT_UNDERSTOOD: 'Не распознал музыкальную команду, попробуйте «включи рок» или «моя волна»',
};

const recentPhrases: string[] = [];

export function pickPersonalityReply(
  phraseSet: string,
  params: Record<string, string> = {},
  fallback?: string,
): MaksVoiceReply {
  const hour = new Date().getHours();
  const isNight = hour >= 23 || hour < 8;
  const key = isNight && allResponses[`${phraseSet}_NIGHT`] ? `${phraseSet}_NIGHT` : phraseSet;
  const entries = allResponses[key] ?? [];

  let chosen: PhraseOption | undefined;
  if (entries.length > 0) {
    const byNight = entries.filter((e) => Boolean(e.night) === isNight);
    const pool = byNight.length > 0 ? byNight : entries;
    const fresh = pool.filter((e) => !recentPhrases.includes(e.text));
    const list = fresh.length > 0 ? fresh : pool;
    const total = list.reduce((acc, e) => acc + Math.max(0.1, e.weight ?? 1), 0);
    let r = Math.random() * total;
    for (const item of list) {
      r -= Math.max(0.1, item.weight ?? 1);
      if (r <= 0) {
        chosen = item;
        break;
      }
    }
    chosen ??= list[0];
    recentPhrases.push(chosen.text);
    if (recentPhrases.length > 5) recentPhrases.shift();
  }

  let template = chosen?.text ?? fallback ?? DEFAULT_FALLBACKS[phraseSet] ?? '';
  for (const [k, v] of Object.entries(params)) {
    template = template.replaceAll(`{${k}}`, v);
  }
  template = template.replace(/\{[a-zA-Z0-9_]+\}/g, '').trim();

  const baseRate = isNight ? 0.94 : 1.04;
  const pct = chosen?.rate ? Number(chosen.rate.replace(/[%+]/g, '')) : 0;
  const speechRate = Number.isFinite(pct) ? Math.min(1.3, Math.max(0.8, baseRate * (1 + pct / 100))) : baseRate;

  const basePitch = isNight ? 0.92 : 0.96;
  const hz = chosen?.pitch ? Number(chosen.pitch.replace(/hz|\+/gi, '')) : 0;
  const speechPitch = Number.isFinite(hz) ? Math.min(1.2, Math.max(0.8, basePitch + hz / 150)) : basePitch;

  return {
    displayText: cleanSpeechText(template),
    emotion: chosen?.emotion ?? 'neutral',
    speechRate,
    speechPitch,
  };
}

export const DEMO_LOCAL_LIBRARY: LocalTrackItem[] = [
  {
    id: 'demo-1',
    artist: 'Ария',
    title: 'Штиль',
    album: 'Химера',
    genre: 'rusrock metal',
    durationSec: 218,
    isRussian: true,
    synthRootHz: 146.83,
    synthMood: 'rock',
  },
  {
    id: 'demo-2',
    artist: 'Ария',
    title: 'Беспечный ангел',
    album: 'Штиль',
    genre: 'rusrock metal',
    durationSec: 240,
    isRussian: true,
    synthRootHz: 164.81,
    synthMood: 'rock',
  },
  {
    id: 'demo-3',
    artist: 'Король и Шут',
    title: 'Лесник',
    album: 'Будь как дома, Путник',
    genre: 'rusrock punk',
    durationSec: 192,
    isRussian: true,
    synthRootHz: 196.0,
    synthMood: 'upbeat',
  },
  {
    id: 'demo-4',
    artist: 'Кино',
    title: 'Группа крови',
    album: 'Группа крови',
    genre: 'rusrock postpunk',
    durationSec: 285,
    isRussian: true,
    synthRootHz: 130.81,
    synthMood: 'dark',
  },
  {
    id: 'demo-5',
    artist: 'Сплин',
    title: 'Выхода нет',
    album: 'Гранатовый альбом',
    genre: 'rusrock indie',
    durationSec: 226,
    isRussian: true,
    synthRootHz: 174.61,
    synthMood: 'dark',
  },
  {
    id: 'demo-6',
    artist: 'Ludovico Einaudi',
    title: 'Experience',
    album: 'In a Time Lapse',
    genre: 'classical ambient',
    durationSec: 315,
    isRussian: false,
    synthRootHz: 220.0,
    synthMood: 'calm',
  },
  {
    id: 'demo-7',
    artist: 'Daft Punk',
    title: 'Instant Crush',
    album: 'Random Access Memories',
    genre: 'electronics pop dance',
    durationSec: 337,
    isRussian: false,
    synthRootHz: 185.0,
    synthMood: 'upbeat',
  },
  {
    id: 'demo-8',
    artist: 'Metallica',
    title: 'Nothing Else Matters',
    album: 'Metallica',
    genre: 'rock metal classicmetal',
    durationSec: 388,
    isRussian: false,
    synthRootHz: 164.81,
    synthMood: 'rock',
  },
];
