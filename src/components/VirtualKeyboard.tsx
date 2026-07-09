import React, { useState, useEffect, useRef } from 'react';
import { motion, AnimatePresence } from 'motion/react';
import { Globe, Smile, Mic, Delete, RefreshCw, Clipboard, Check, Volume2, Search, ArrowRight, ShieldCheck } from 'lucide-react';
import { KeyboardSettings, CustomTheme, MLModelStats, KoreanLayout } from '../types';
import { assembleJamos, composeCheonjiinVowels, resolveCheonjiinBuffer, composeGeomjigeulVowels, isVowel, STROKE_ADDITIONS, DOUBLE_CONSONANTS } from '../utils/hangul';
import { getAutocompleteSuggestions, predictNextWords, getSentenceCorrection } from '../utils/keyboardEngine';

interface VirtualKeyboardProps {
  settings: KeyboardSettings;
  setSettings: React.Dispatch<React.SetStateAction<KeyboardSettings>>;
  textValue: string;
  setTextValue: (val: string) => void;
  focusedInputId: string | null;
  clipboard: string[];
  addToClipboard: (text: string) => void;
  mlStats: MLModelStats;
  updateMLStats: (typedWord: string, prevWord: string) => void;
  incrementCorrections: () => void;
}

export default function VirtualKeyboard({
  settings,
  setSettings,
  textValue,
  setTextValue,
  focusedInputId,
  clipboard,
  addToClipboard,
  mlStats,
  updateMLStats,
  incrementCorrections
}: VirtualKeyboardProps) {
  const [isShifted, setIsShifted] = useState(false);
  const [currentLanguage, setCurrentLanguage] = useState<'ko' | 'en'>('ko');
  const [activeTab, setActiveTab] = useState<'keyboard' | 'emoji' | 'voice'>('keyboard');
  
  // Emoji tab categorization
  const [emojiCategory, setEmojiCategory] = useState<'faces' | 'animals' | 'objects' | 'symbols'>('faces');
  
  // Jamo composition buffers
  // For QWERTY Dubeolsik, we buffer the current word's Jamos
  const [koQwertyJamos, setKoQwertyJamos] = useState<string[]>([]);
  // For Cheonjiin, we track the state of composition
  const [cheonjiinJamos, setCheonjiinJamos] = useState<string[]>([]);
  // For Naratgul
  const [naratgulJamos, setNaratgulJamos] = useState<string[]>([]);
  // For Geomjigeul
  const [geomjigeulJamos, setGeomjigeulJamos] = useState<string[]>([]);
  
  // Drag tracking for Geomjigeul swipe input
  const geomjigeulDragStart = useRef<{ x: number; y: number; key: string } | null>(null);
  const [geomjigeulActiveDrag, setGeomjigeulActiveDrag] = useState<{ key: string; direction: 'left' | 'right' | null } | null>(null);
  
  // Feedback popup for pressed key (mobile design craft)
  const [activePopupKey, setActivePopupKey] = useState<string | null>(null);
  const [popupPosition, setPopupPosition] = useState<{ x: number; y: number } | null>(null);
  
  // Voice Input states
  const [isListening, setIsListening] = useState(false);
  const [voiceTranscript, setVoiceTranscript] = useState('');
  const recognitionRef = useRef<any>(null);
  
  // Suggestion correction state for the [수정] (Correct) mechanism
  const [correctionCandidate, setCorrectionCandidate] = useState<{ original: string; corrected: string; sentence: string } | null>(null);
  const [showCorrectionToast, setShowCorrectionToast] = useState(false);
  const [showLayoutMenu, setShowLayoutMenu] = useState(false);

  const [windowDimensions, setWindowDimensions] = useState({
    width: typeof window !== 'undefined' ? window.innerWidth : 360,
    height: typeof window !== 'undefined' ? window.innerHeight : 640
  });

  useEffect(() => {
    const handleResize = () => {
      setWindowDimensions({
        width: window.innerWidth,
        height: window.innerHeight
      });
    };
    window.addEventListener('resize', handleResize);
    return () => window.removeEventListener('resize', handleResize);
  }, []);

  const handleLayoutChange = (layout: KoreanLayout) => {
    setSettings(prev => ({
      ...prev,
      activeKoreanLayout: layout
    }));
    setShowLayoutMenu(false);
  };

  const theme = settings.customTheme;
  const isSecurityMode = settings.preventPasswordHints && focusedInputId?.toLowerCase().includes('password');

  // Load language settings
  useEffect(() => {
    if (settings.languages.ko && !settings.languages.en) {
      setCurrentLanguage('ko');
    } else if (!settings.languages.ko && settings.languages.en) {
      setCurrentLanguage('en');
    }
  }, [settings.languages]);

  // Track the typed sentences to suggest corrections
  useEffect(() => {
    if (isSecurityMode) {
      setCorrectionCandidate(null);
      return;
    }
    const candidate = getSentenceCorrection(textValue, currentLanguage === 'ko');
    setCorrectionCandidate(candidate);
  }, [textValue, currentLanguage, isSecurityMode]);

  // Reset composition buffers when language or layout changes
  useEffect(() => {
    commitComposition();
  }, [currentLanguage, settings.activeKoreanLayout]);

  // Auto-Speech recognition setup
  useEffect(() => {
    const SpeechRecognition = (window as any).SpeechRecognition || (window as any).webkitSpeechRecognition;
    if (SpeechRecognition) {
      const rec = new SpeechRecognition();
      rec.continuous = true;
      rec.interimResults = true;
      rec.lang = currentLanguage === 'ko' ? 'ko-KR' : 'en-US';
      
      rec.onresult = (e: any) => {
        let interim = '';
        let final = '';
        for (let i = e.resultIndex; i < e.results.length; ++i) {
          if (e.results[i].isFinal) {
            final += e.results[i][0].transcript;
          } else {
            interim += e.results[i][0].transcript;
          }
        }
        setVoiceTranscript(final || interim);
      };

      rec.onerror = (event: any) => {
        console.error("Speech recognition error:", event.error);
        setIsListening(false);
        if (event.error === 'not-allowed') {
          setVoiceTranscript('마이크 권한이 거부되었습니다. 브라우저 주소창 옆 설정에서 마이크를 허용해 주세요.');
        } else if (event.error === 'no-speech') {
          setVoiceTranscript('인식된 음성이 없습니다. 마이크를 켜고 다시 말씀해 주세요.');
        } else if (event.error === 'network') {
          setVoiceTranscript('네트워크 연결 오류가 발생했습니다.');
        } else {
          setVoiceTranscript(`인식 오류 (${event.error}). 다시 시도해 주세요.`);
        }
      };

      rec.onend = () => {
        setIsListening(false);
      };

      recognitionRef.current = rec;
    }
  }, [currentLanguage]);

  // Vibrate helper
  const triggerVibration = () => {
    if (settings.vibrateOnPress && navigator.vibrate) {
      navigator.vibrate(20);
    }
  };

  // Commit current composition to final text
  const commitComposition = () => {
    setKoQwertyJamos([]);
    setCheonjiinJamos([]);
    setNaratgulJamos([]);
    setGeomjigeulJamos([]);
  };

  // Insert a text string at current position
  const insertText = (str: string) => {
    triggerVibration();
    setTextValue(textValue + str);
  };

  // Backspace key
  const handleBackspace = () => {
    triggerVibration();
    
    // Check if we have active Hangul Jamo buffers first
    if (currentLanguage === 'ko') {
      if (settings.activeKoreanLayout === 'qwerty' && koQwertyJamos.length > 0) {
        const nextJamos = koQwertyJamos.slice(0, -1);
        setKoQwertyJamos(nextJamos);
        
        // Replace the last composing word in the textValue
        const currentWordComposed = assembleJamos(koQwertyJamos);
        const nextWordComposed = assembleJamos(nextJamos);
        
        if (textValue.endsWith(currentWordComposed)) {
          setTextValue(textValue.slice(0, -currentWordComposed.length) + nextWordComposed);
        }
        return;
      }
      
      if (settings.activeKoreanLayout === 'cheonjiin' && cheonjiinJamos.length > 0) {
        const nextJamos = cheonjiinJamos.slice(0, -1);
        setCheonjiinJamos(nextJamos);
        const currentComposed = assembleJamos(resolveCheonjiinBuffer(cheonjiinJamos));
        const nextComposed = assembleJamos(resolveCheonjiinBuffer(nextJamos));
        if (textValue.endsWith(currentComposed)) {
          setTextValue(textValue.slice(0, -currentComposed.length) + nextComposed);
        }
        return;
      }

      if (settings.activeKoreanLayout === 'naratgul' && naratgulJamos.length > 0) {
        const nextJamos = naratgulJamos.slice(0, -1);
        setNaratgulJamos(nextJamos);
        const currentComposed = assembleJamos(naratgulJamos);
        const nextComposed = assembleJamos(nextJamos);
        if (textValue.endsWith(currentComposed)) {
          setTextValue(textValue.slice(0, -currentComposed.length) + nextComposed);
        }
        return;
      }

      if (settings.activeKoreanLayout === 'geomjigeul' && geomjigeulJamos.length > 0) {
        const nextJamos = geomjigeulJamos.slice(0, -1);
        setGeomjigeulJamos(nextJamos);
        const currentComposed = assembleJamos(composeGeomjigeulVowels(geomjigeulJamos));
        const nextComposed = assembleJamos(composeGeomjigeulVowels(nextJamos));
        if (textValue.endsWith(currentComposed)) {
          setTextValue(textValue.slice(0, -currentComposed.length) + nextComposed);
        }
        return;
      }
    }

    // Default backspace (removes last char)
    if (textValue.length > 0) {
      setTextValue(textValue.slice(0, -1));
    }
  };

  // Space key
  const handleSpace = () => {
    triggerVibration();
    
    // Extract the last typed word to update machine learning stats
    const words = textValue.trim().split(/\s+/);
    const lastWord = words[words.length - 1] || '';
    const prevWord = words[words.length - 2] || '';
    
    // Commit composition
    commitComposition();
    
    if (lastWord && !isSecurityMode) {
      updateMLStats(lastWord, prevWord);
    }
    
    setTextValue(textValue + ' ');
  };

  // Enter key
  const handleEnter = () => {
    triggerVibration();
    
    // If there is an active correction candidate, let's auto-correct on Enter!
    if (correctionCandidate) {
      applySentenceCorrection();
    } else {
      commitComposition();
      setTextValue(textValue + '\n');
    }
  };

  // Handle QWERTY Key taps
  const handleQwertyKey = (char: string, e: React.MouseEvent<HTMLButtonElement>) => {
    triggerVibration();
    
    // Show premium visual key preview popup
    const rect = e.currentTarget.getBoundingClientRect();
    setPopupPosition({ x: rect.left + rect.width / 2, y: rect.top - 20 });
    setActivePopupKey(char);
    setTimeout(() => {
      setActivePopupKey(null);
    }, 150);

    if (currentLanguage === 'en') {
      const letter = isShifted ? char.toUpperCase() : char.toLowerCase();
      insertText(letter);
      setIsShifted(false);
    } else {
      // Korean QWERTY layout input mapping
      let inputChar = char;
      if (isShifted) {
        // Handle shifted Korean keys (ㅃ, ㅉ, ㄸ, ㄲ, ㅆ, ㅒ, ㅖ)
        const shiftKoMap: Record<string, string> = {
          'ㅂ': 'ㅃ', 'ㅈ': 'ㅉ', 'ㄷ': 'ㄸ', 'ㄱ': 'ㄲ', 'ㅅ': 'ㅆ',
          'ㅐ': 'ㅒ', 'ㅔ': 'ㅖ'
        };
        inputChar = shiftKoMap[char] || char;
        setIsShifted(false);
      }
      
      const newJamos = [...koQwertyJamos, inputChar];
      setKoQwertyJamos(newJamos);
      
      // Update textValue: replace previous composing state with new composing state
      const prevComposed = assembleJamos(koQwertyJamos);
      const newComposed = assembleJamos(newJamos);
      
      if (textValue.endsWith(prevComposed)) {
        setTextValue(textValue.slice(0, -prevComposed.length) + newComposed);
      } else {
        setTextValue(textValue + newComposed);
      }
    }
  };

  // Handle Cheonjiin Vowel combinations
  const processCheonjiinVowelInput = (vowelKey: string) => {
    // Collect the vowel input key
    const newJamos = [...cheonjiinJamos, vowelKey];
    setCheonjiinJamos(newJamos);
    
    const prevComposed = assembleJamos(resolveCheonjiinBuffer(cheonjiinJamos));
    const newComposed = assembleJamos(resolveCheonjiinBuffer(newJamos));
    
    if (textValue.endsWith(prevComposed)) {
      setTextValue(textValue.slice(0, -prevComposed.length) + newComposed);
    } else {
      setTextValue(textValue + newComposed);
    }
  };

  // Handle Cheonjiin Consonants
  const processCheonjiinConsonantInput = (baseConsonants: string[]) => {
    // If the last jamo is from the same group, cycle them!
    // Example: click 'ㄱㅋ' -> 'ㄱ'. click again -> 'ㅋ'. click again -> 'ㄲ'.
    let newJamos = [...cheonjiinJamos];
    
    if (newJamos.length > 0) {
      const last = newJamos[newJamos.length - 1];
      const matchIndex = baseConsonants.indexOf(last);
      
      if (matchIndex !== -1) {
        // Cycle to the next consonant in the group
        const nextIndex = (matchIndex + 1) % baseConsonants.length;
        newJamos[newJamos.length - 1] = baseConsonants[nextIndex];
        setCheonjiinJamos(newJamos);
        
        const prevComposed = assembleJamos(resolveCheonjiinBuffer(cheonjiinJamos));
        const newComposed = assembleJamos(resolveCheonjiinBuffer(newJamos));
        if (textValue.endsWith(prevComposed)) {
          setTextValue(textValue.slice(0, -prevComposed.length) + newComposed);
        }
        return;
      }
    }
    
    // Add new consonant
    newJamos.push(baseConsonants[0]);
    setCheonjiinJamos(newJamos);
    
    const prevComposed = assembleJamos(resolveCheonjiinBuffer(cheonjiinJamos));
    const newComposed = assembleJamos(resolveCheonjiinBuffer(newJamos));
    if (textValue.endsWith(prevComposed)) {
      setTextValue(textValue.slice(0, -prevComposed.length) + newComposed);
    } else {
      setTextValue(textValue + newComposed);
    }
  };

  // Cheonjiin functional keys
  const handleCheonjiinStrokeAddition = () => {
    triggerVibration();
    if (cheonjiinJamos.length === 0) return;
    
    let nextJamos = [...cheonjiinJamos];
    const lastIdx = nextJamos.length - 1;
    const lastJamo = nextJamos[lastIdx];
    
    // Check if stroke can be added
    if (STROKE_ADDITIONS[lastJamo]) {
      nextJamos[lastIdx] = STROKE_ADDITIONS[lastJamo];
      setCheonjiinJamos(nextJamos);
      
      const prevComposed = assembleJamos(resolveCheonjiinBuffer(cheonjiinJamos));
      const newComposed = assembleJamos(resolveCheonjiinBuffer(nextJamos));
      if (textValue.endsWith(prevComposed)) {
        setTextValue(textValue.slice(0, -prevComposed.length) + newComposed);
      }
    }
  };

  const handleCheonjiinDoubleConsonant = () => {
    triggerVibration();
    if (cheonjiinJamos.length === 0) return;
    
    let nextJamos = [...cheonjiinJamos];
    const lastIdx = nextJamos.length - 1;
    const lastJamo = nextJamos[lastIdx];
    
    if (DOUBLE_CONSONANTS[lastJamo]) {
      nextJamos[lastIdx] = DOUBLE_CONSONANTS[lastJamo];
      setCheonjiinJamos(nextJamos);
      
      const prevComposed = assembleJamos(resolveCheonjiinBuffer(cheonjiinJamos));
      const newComposed = assembleJamos(resolveCheonjiinBuffer(nextJamos));
      if (textValue.endsWith(prevComposed)) {
        setTextValue(textValue.slice(0, -prevComposed.length) + newComposed);
      }
    }
  };

  // Handle Naratgul Inputs
  const handleNaratgulKey = (char: string) => {
    triggerVibration();
    let nextJamos = [...naratgulJamos, char];
    setNaratgulJamos(nextJamos);
    
    const prevComposed = assembleJamos(naratgulJamos);
    const newComposed = assembleJamos(nextJamos);
    
    if (textValue.endsWith(prevComposed)) {
      setTextValue(textValue.slice(0, -prevComposed.length) + newComposed);
    } else {
      setTextValue(textValue + newComposed);
    }
  };

  const handleNaratgulStrokeAddition = () => {
    triggerVibration();
    if (naratgulJamos.length === 0) return;
    
    let nextJamos = [...naratgulJamos];
    const lastIdx = nextJamos.length - 1;
    const lastJamo = nextJamos[lastIdx];
    
    if (STROKE_ADDITIONS[lastJamo]) {
      nextJamos[lastIdx] = STROKE_ADDITIONS[lastJamo];
      setNaratgulJamos(nextJamos);
      
      const prevComposed = assembleJamos(naratgulJamos);
      const newComposed = assembleJamos(nextJamos);
      if (textValue.endsWith(prevComposed)) {
        setTextValue(textValue.slice(0, -prevComposed.length) + newComposed);
      }
    }
  };

  const handleNaratgulDoubleConsonant = () => {
    triggerVibration();
    if (naratgulJamos.length === 0) return;
    
    let nextJamos = [...naratgulJamos];
    const lastIdx = nextJamos.length - 1;
    const lastJamo = nextJamos[lastIdx];
    
    if (DOUBLE_CONSONANTS[lastJamo]) {
      nextJamos[lastIdx] = DOUBLE_CONSONANTS[lastJamo];
      setNaratgulJamos(nextJamos);
      
      const prevComposed = assembleJamos(naratgulJamos);
      const newComposed = assembleJamos(nextJamos);
      if (textValue.endsWith(prevComposed)) {
        setTextValue(textValue.slice(0, -prevComposed.length) + newComposed);
      }
    }
  };

  // Handle Geomjigeul Inputs
  const handleGeomjigeulKey = (char: string) => {
    triggerVibration();
    let nextJamos = [...geomjigeulJamos, char];
    setGeomjigeulJamos(nextJamos);
    
    const prevComposed = assembleJamos(composeGeomjigeulVowels(geomjigeulJamos));
    const newComposed = assembleJamos(composeGeomjigeulVowels(nextJamos));
    
    if (textValue.endsWith(prevComposed)) {
      setTextValue(textValue.slice(0, -prevComposed.length) + newComposed);
    } else {
      setTextValue(textValue + newComposed);
    }
  };

  const handleGeomjigeulDrag = (char: string, direction: 'left' | 'right') => {
    triggerVibration();
    const GEOMJIGEUL_SWIPES: Record<string, { left?: string; right?: string }> = {
      'ㄱ': { left: 'ㅋ', right: 'ㄲ' },
      'ㄷ': { left: 'ㅌ', right: 'ㄸ' },
      'ㅂ': { left: 'ㅍ', right: 'ㅃ' },
      'ㅈ': { left: 'ㅊ', right: 'ㅉ' },
      'ㅇ': { left: 'ㅎ' },
      'ㅅ': { right: 'ㅆ' }
    };
    const swipeMap = GEOMJIGEUL_SWIPES[char];
    if (swipeMap && swipeMap[direction]) {
      const resolvedChar = swipeMap[direction]!;
      let nextJamos = [...geomjigeulJamos, resolvedChar];
      setGeomjigeulJamos(nextJamos);
      
      const prevComposed = assembleJamos(composeGeomjigeulVowels(geomjigeulJamos));
      const newComposed = assembleJamos(composeGeomjigeulVowels(nextJamos));
      
      if (textValue.endsWith(prevComposed)) {
        setTextValue(textValue.slice(0, -prevComposed.length) + newComposed);
      } else {
        setTextValue(textValue + newComposed);
      }
    } else {
      // Fallback
      handleGeomjigeulKey(char);
    }
  };

  const handleGeomjigeulStrokeAddition = () => {
    triggerVibration();
    if (geomjigeulJamos.length === 0) return;
    
    let nextJamos = [...geomjigeulJamos];
    const lastIdx = nextJamos.length - 1;
    const lastJamo = nextJamos[lastIdx];
    
    if (STROKE_ADDITIONS[lastJamo]) {
      nextJamos[lastIdx] = STROKE_ADDITIONS[lastJamo];
      setGeomjigeulJamos(nextJamos);
      
      const prevComposed = assembleJamos(composeGeomjigeulVowels(geomjigeulJamos));
      const newComposed = assembleJamos(composeGeomjigeulVowels(nextJamos));
      if (textValue.endsWith(prevComposed)) {
        setTextValue(textValue.slice(0, -prevComposed.length) + newComposed);
      }
    }
  };

  const handleGeomjigeulDoubleConsonant = () => {
    triggerVibration();
    if (geomjigeulJamos.length === 0) return;
    
    let nextJamos = [...geomjigeulJamos];
    const lastIdx = nextJamos.length - 1;
    const lastJamo = nextJamos[lastIdx];
    
    if (DOUBLE_CONSONANTS[lastJamo]) {
      nextJamos[lastIdx] = DOUBLE_CONSONANTS[lastJamo];
      setGeomjigeulJamos(nextJamos);
      
      const prevComposed = assembleJamos(composeGeomjigeulVowels(geomjigeulJamos));
      const newComposed = assembleJamos(composeGeomjigeulVowels(nextJamos));
      if (textValue.endsWith(prevComposed)) {
        setTextValue(textValue.slice(0, -prevComposed.length) + newComposed);
      }
    }
  };

  // Handle suggestion click
  const handleSuggestionClick = (suggestion: string) => {
    triggerVibration();
    
    // Find the last composing word or last token in textValue
    const words = textValue.trim().split(/\s+/);
    const lastWord = words[words.length - 1] || '';
    
    // Commit composition state
    commitComposition();
    
    // Replace the active prefix with the suggestion
    if (textValue.endsWith(lastWord)) {
      const preceding = textValue.slice(0, -lastWord.length);
      setTextValue(preceding + suggestion + ' ');
    } else {
      setTextValue(textValue + suggestion + ' ');
    }
    
    // ML update
    if (!isSecurityMode) {
      updateMLStats(suggestion, words[words.length - 2] || '');
    }
  };

  // Apply corrected sentence
  const applySentenceCorrection = () => {
    if (!correctionCandidate) return;
    setTextValue(correctionCandidate.sentence);
    incrementCorrections();
    setCorrectionCandidate(null);
    setShowCorrectionToast(true);
    setTimeout(() => {
      setShowCorrectionToast(false);
    }, 2500);
  };

  // Start Voice recognition
  const toggleVoiceListening = () => {
    triggerVibration();
    if (!recognitionRef.current) {
      // Simulation mode
      if (!isListening) {
        setIsListening(true);
        setVoiceTranscript('인식 중...');
        setTimeout(() => {
          setVoiceTranscript(currentLanguage === 'ko' ? '안녕하세요 맞춤법 자동 추천 키보드입니다' : 'hello this is a custom keyboard');
        }, 1200);
        setTimeout(() => {
          setIsListening(false);
        }, 2500);
      } else {
        setIsListening(false);
      }
      return;
    }

    if (isListening) {
      recognitionRef.current.stop();
      setIsListening(false);
    } else {
      setVoiceTranscript('');
      setIsListening(true);
      try {
        recognitionRef.current.start();
      } catch (err) {
        console.error(err);
      }
    }
  };

  const switchToVoiceTab = () => {
    triggerVibration();
    setActiveTab('voice');
    setVoiceTranscript('');
    setIsListening(true);

    if (recognitionRef.current) {
      try {
        try { recognitionRef.current.stop(); } catch (e) {}
        recognitionRef.current.start();
      } catch (err) {
        console.error("Speech recognition auto-start error:", err);
      }
    } else {
      // Simulation mode
      setVoiceTranscript('인식 중...');
      setTimeout(() => {
        setVoiceTranscript(currentLanguage === 'ko' ? '안녕하세요 맞춤법 자동 추천 키보드입니다' : 'hello this is a custom keyboard');
      }, 1200);
      setTimeout(() => {
        setIsListening(false);
      }, 2500);
    }
  };

  const commitVoiceInput = () => {
    if (voiceTranscript && voiceTranscript !== '인식 중...') {
      insertText(voiceTranscript);
      setVoiceTranscript('');
      setActiveTab('keyboard');
    }
  };

  // Generate layouts keyboard key row list
  const getQwertyRow = (rowNum: number): string[] => {
    const rowsKo = [
      ['ㅂ', 'ㅈ', 'ㄷ', 'ㄱ', 'ㅅ', 'ㅛ', 'ㅕ', 'ㅑ', 'ㅐ', 'ㅔ'],
      ['ㅁ', 'ㄴ', 'ㅇ', 'ㄹ', 'ㅎ', 'ㅗ', 'ㅓ', 'ㅏ', 'ㅣ'],
      ['ㅋ', 'ㅌ', 'ㅊ', 'ㅍ', 'ㅠ', 'ㅜ', 'ㅡ']
    ];
    
    const rowsEn = [
      ['q', 'w', 'e', 'r', 't', 'y', 'u', 'i', 'o', 'p'],
      ['a', 's', 'd', 'f', 'g', 'h', 'j', 'k', 'l'],
      ['z', 'x', 'c', 'v', 'b', 'n', 'm']
    ];

    const currentMap = currentLanguage === 'ko' ? rowsKo : rowsEn;
    return currentMap[rowNum - 1];
  };

  // Key Styling Helper based on Settings (Shape & Themes)
  const getKeyShapeClass = () => {
    switch (theme.keyShape) {
      case 'round': return 'rounded-full';
      case 'pill': return 'rounded-3xl';
      case 'square': return 'rounded-none';
      case 'rectangular': return 'rounded-md';
      case 'borderless': return 'rounded-none border-transparent bg-transparent shadow-none';
      default: return 'rounded-lg';
    }
  };

  // Compile active suggestions list for suggestion bar
  const getSuggestions = (): string[] => {
    if (isSecurityMode) return [];
    
    // Find last typed token
    const words = textValue.split(/\s+/);
    const currentWord = words[words.length - 1] || '';
    
    if (currentWord) {
      // Suggest autocomplete
      return getAutocompleteSuggestions(currentWord, currentLanguage === 'ko', mlStats);
    } else {
      // Suggest next-word prediction based on previous word
      const lastWord = words[words.length - 2] || '';
      return predictNextWords(lastWord, currentLanguage === 'ko', mlStats);
    }
  };

  const currentSuggestions = getSuggestions();

  // Geomjigeul drag gesture handlers
  const getDragHandlers = (char: string) => {
    return {
      onPointerDown: (e: React.PointerEvent<HTMLButtonElement>) => {
        e.currentTarget.setPointerCapture(e.pointerId);
        geomjigeulDragStart.current = { x: e.clientX, y: e.clientY, key: char };
        setGeomjigeulActiveDrag({ key: char, direction: null });
      },
      onPointerMove: (e: React.PointerEvent<HTMLButtonElement>) => {
        if (!geomjigeulDragStart.current || geomjigeulDragStart.current.key !== char) return;
        const diffX = e.clientX - geomjigeulDragStart.current.x;
        if (diffX < -30) {
          setGeomjigeulActiveDrag({ key: char, direction: 'left' });
        } else if (diffX > 30) {
          setGeomjigeulActiveDrag({ key: char, direction: 'right' });
        } else {
          setGeomjigeulActiveDrag({ key: char, direction: null });
        }
      },
      onPointerUp: (e: React.PointerEvent<HTMLButtonElement>) => {
        if (!geomjigeulDragStart.current || geomjigeulDragStart.current.key !== char) return;
        e.currentTarget.releasePointerCapture(e.pointerId);
        const diffX = e.clientX - geomjigeulDragStart.current.x;
        geomjigeulDragStart.current = null;
        setGeomjigeulActiveDrag(null);
        
        if (diffX < -30) {
          handleGeomjigeulDrag(char, 'left');
        } else if (diffX > 30) {
          handleGeomjigeulDrag(char, 'right');
        } else {
          handleGeomjigeulKey(char);
        }
      },
      onPointerCancel: (e: React.PointerEvent<HTMLButtonElement>) => {
        e.currentTarget.releasePointerCapture(e.pointerId);
        geomjigeulDragStart.current = null;
        setGeomjigeulActiveDrag(null);
      }
    };
  };

  const renderDragIndicators = (char: string) => {
    const swipes: Record<string, { left?: string; right?: string }> = {
      'ㄱ': { left: 'ㅋ', right: 'ㄲ' },
      'ㄷ': { left: 'ㅌ', right: 'ㄸ' },
      'ㅂ': { left: 'ㅍ', right: 'ㅃ' },
      'ㅈ': { left: 'ㅊ', right: 'ㅉ' },
      'ㅇ': { left: 'ㅎ' },
      'ㅅ': { right: 'ㅆ' }
    };
    const swipeMap = swipes[char];
    if (!swipeMap) return null;
    
    const isActive = geomjigeulActiveDrag?.key === char;
    const currentDir = isActive ? geomjigeulActiveDrag?.direction : null;

    return (
      <div className="absolute inset-x-0 bottom-0.5 flex justify-between px-1 text-[8px] pointer-events-none select-none opacity-50">
        <span className={`transition-all duration-150 ${currentDir === 'left' ? 'text-amber-500 font-extrabold scale-125 opacity-100' : ''}`}>
          {swipeMap.left ? `←${swipeMap.left}` : ''}
        </span>
        <span className={`transition-all duration-150 ${currentDir === 'right' ? 'text-amber-500 font-extrabold scale-125 opacity-100' : ''}`}>
          {swipeMap.right ? `${swipeMap.right}→` : ''}
        </span>
      </div>
    );
  };

  const isLandscape = windowDimensions.width > windowDimensions.height;
  const maxAllowedHeight = isLandscape 
    ? Math.min(200, Math.floor(windowDimensions.height * 0.48)) 
    : Math.floor(windowDimensions.height * 0.48);
  const actualKeyboardHeight = Math.min(settings.keyboardHeight, maxAllowedHeight);

  // Dynamic sizing factors to prevent cutoff in restricted/landscape environments
  const isCompact = isLandscape || actualKeyboardHeight < 240;

  const suggestionBarHeight = isCompact ? 28 : 36;
  const paddingAllowance = isCompact ? 4 : 12;
  const usableHeightForKeys = actualKeyboardHeight - suggestionBarHeight - paddingAllowance;

  // Proportional height distribution (ratio: 12% for top numbers, 18% for bottom toolbar, remaining for key layouts)
  const topNumberRowHeight = Math.max(14, Math.min(24, Math.floor(usableHeightForKeys * 0.12)));
  const bottomToolbarHeight = Math.max(22, Math.min(36, Math.floor(usableHeightForKeys * 0.18)));
  const remainingHeightForLayout = usableHeightForKeys - topNumberRowHeight - bottomToolbarHeight - (isCompact ? 4 : 10);

  // Key heights for different layouts
  const qwertyKeyHeight = Math.max(18, Math.floor(remainingHeightForLayout / 3));
  const cheonjiinKeyHeight = Math.max(16, Math.floor(remainingHeightForLayout / 4));
  const naratgulKeyHeight = Math.max(16, Math.floor(remainingHeightForLayout / 4));
  const geomjigeulKeyHeight = Math.max(16, Math.floor(remainingHeightForLayout / 4));

  return (
    <div 
      className="select-none flex flex-col justify-end w-full relative"
      style={{
        height: `${actualKeyboardHeight}px`,
        backgroundColor: theme.isDark ? '#121214' : '#F1F3F5',
        fontFamily: settings.fontFamily,
        borderTop: theme.isDark ? '1px solid #2D3139' : '1px solid #E2E8F0',
      }}
      id="custom-virtual-keyboard"
    >
      <style>{`
        #custom-virtual-keyboard .top-number-row button {
          height: ${topNumberRowHeight}px !important;
          font-size: ${isCompact ? '10px' : '12px'} !important;
        }
        #custom-virtual-keyboard .qwerty-row button {
          height: ${qwertyKeyHeight}px !important;
          font-size: ${isCompact ? '11px' : '14px'} !important;
        }
        #custom-virtual-keyboard .cheonjiin-layout button {
          height: ${cheonjiinKeyHeight}px !important;
          font-size: ${isCompact ? '11px' : '14px'} !important;
        }
        #custom-virtual-keyboard .naratgul-layout button {
          height: ${naratgulKeyHeight}px !important;
          font-size: ${isCompact ? '11px' : '14px'} !important;
        }
        #custom-virtual-keyboard .geomjigeul-layout button {
          height: ${geomjigeulKeyHeight}px !important;
          font-size: ${isCompact ? '10px' : '13px'} !important;
        }
        #custom-virtual-keyboard .bottom-toolbar-row button {
          height: ${bottomToolbarHeight}px !important;
          font-size: ${isCompact ? '10px' : '12px'} !important;
        }
      `}</style>
      {/* 1. Keyboard Preview Popup (Visual feedback) */}
      <AnimatePresence>
        {activePopupKey && popupPosition && (
          <motion.div
            initial={{ opacity: 0, scale: 0.5, y: 10 }}
            animate={{ opacity: 1, scale: 1.2, y: -25 }}
            exit={{ opacity: 0, scale: 0.8 }}
            className="absolute z-50 px-4 py-2 text-xl font-bold bg-white text-black rounded-lg shadow-xl border border-gray-200 pointer-events-none transform -translate-x-1/2"
            style={{
              left: popupPosition.x - (document.getElementById('custom-virtual-keyboard')?.getBoundingClientRect().left || 0),
              top: popupPosition.y - (document.getElementById('custom-virtual-keyboard')?.getBoundingClientRect().top || 0) - 20,
            }}
          >
            {activePopupKey}
          </motion.div>
        )}
      </AnimatePresence>

      {/* 2. Suggestion Bar (추천 바) */}
      <div 
        className="flex items-center justify-between px-2 border-b overflow-x-auto text-xs font-medium"
        style={{
          height: `${suggestionBarHeight}px`,
          backgroundColor: theme.isDark ? '#1E1E22' : '#E9ECEF',
          borderColor: theme.isDark ? '#2D3139' : '#DEE2E6',
        }}
      >
        {isSecurityMode ? (
          <div className="flex items-center gap-1.5 px-3 py-1 text-red-500 font-bold mx-auto">
            <ShieldCheck className="w-3.5 h-3.5" />
            <span>보안 입력 모드 (비밀번호 입력 힌트 방지)</span>
          </div>
        ) : (
          <div className="flex items-center justify-around w-full divide-x divide-opacity-30 divide-gray-500 overflow-x-auto">
            {/* Clipboard Bar Overlay */}
            {clipboard.length > 0 && (
              <button
                onClick={() => handleSuggestionClick(clipboard[0])}
                className="flex items-center gap-1 px-2.5 py-1 text-sky-500 bg-sky-500/10 hover:bg-sky-500/20 rounded-full shrink-0 transition"
              >
                <Clipboard className="w-3 h-3" />
                <span className="truncate max-w-[80px] font-semibold">{clipboard[0]}</span>
              </button>
            )}

            {/* Main Suggestions */}
            {currentSuggestions.length > 0 ? (
              currentSuggestions.map((suggestion, idx) => (
                <button
                  key={idx}
                  onClick={() => handleSuggestionClick(suggestion)}
                  className="px-3 py-1 font-bold text-center flex-1 truncate transition active:scale-95"
                  style={{ color: theme.isDark ? '#E2E8F0' : '#2D3748' }}
                >
                  {suggestion}
                </button>
              ))
            ) : (
              <span className="text-gray-500 text-[11px] italic text-center w-full">추천 단어가 없습니다</span>
            )}

            {/* [수정] Correct Key suggestion bubble */}
            {correctionCandidate && (
              <button
                onClick={applySentenceCorrection}
                className="flex items-center gap-1.5 px-2.5 py-1 text-amber-500 bg-amber-500/10 hover:bg-amber-500/20 rounded-full font-bold ml-1 shrink-0 animate-pulse border border-amber-500/30"
              >
                <RefreshCw className="w-3 h-3" />
                <span className="text-[10px]">수정: {correctionCandidate.original}→{correctionCandidate.corrected}</span>
              </button>
            )}
          </div>
        )}
      </div>

      {/* 3. Keyboard Main Tab Area */}
      <div className={`flex flex-col justify-between flex-1 overflow-hidden ${isCompact ? 'p-0.5' : 'p-1.5'}`} style={{ color: theme.keyTextColor }}>
        
        {/* TAB 1: Standby Character Keyboard */}
        {activeTab === 'keyboard' && (
          <div className={`flex flex-col justify-between h-full ${isCompact ? 'gap-0.5' : 'gap-1'}`}>
            {/* Dedicated Top Number Row (always visible) */}
            <div className="top-number-row flex justify-between w-full gap-0.5 mb-0.5">
              {['1', '2', '3', '4', '5', '6', '7', '8', '9', '0'].map((num) => (
                <button
                  key={num}
                  onClick={() => { triggerVibration(); insertText(num); }}
                  className={`flex-1 h-[26px] flex items-center justify-center font-bold text-xs shadow-sm transition active:scale-90 ${getKeyShapeClass()}`}
                  style={{
                    backgroundColor: theme.keyBgColor,
                    color: theme.keyTextColor,
                    opacity: 0.95
                  }}
                >
                  {num}
                </button>
              ))}
            </div>
            
            {/* 3A. QWERTY / Standard Rows */}
            {(currentLanguage === 'en' || settings.activeKoreanLayout === 'qwerty') ? (
              <>
                {/* QWERTY Row 1 */}
                <div className="qwerty-row flex justify-center w-full gap-1">
                  {getQwertyRow(1).map((char) => (
                    <button
                      key={char}
                      onClick={(e) => handleQwertyKey(char, e)}
                      className={`flex-1 h-10 flex items-center justify-center font-bold text-sm shadow-sm transition active:scale-90 ${getKeyShapeClass()}`}
                      style={{
                        backgroundColor: theme.keyBgColor,
                        fontSize: `${settings.fontSize}px`
                      }}
                    >
                      {isShifted && currentLanguage === 'en' ? char.toUpperCase() : char}
                    </button>
                  ))}
                </div>

                {/* QWERTY Row 2 */}
                <div className="qwerty-row flex justify-center w-full gap-1 px-[3%]">
                  {getQwertyRow(2).map((char) => (
                    <button
                      key={char}
                      onClick={(e) => handleQwertyKey(char, e)}
                      className={`flex-1 h-10 flex items-center justify-center font-bold text-sm shadow-sm transition active:scale-90 ${getKeyShapeClass()}`}
                      style={{
                        backgroundColor: theme.keyBgColor,
                        fontSize: `${settings.fontSize}px`
                      }}
                    >
                      {isShifted && currentLanguage === 'en' ? char.toUpperCase() : char}
                    </button>
                  ))}
                </div>

                {/* QWERTY Row 3 */}
                <div className="qwerty-row flex justify-center w-full gap-1">
                  {/* Shift toggle */}
                  <button
                    onClick={() => { triggerVibration(); setIsShifted(!isShifted); }}
                    className={`px-3.5 h-10 flex items-center justify-center font-bold text-xs shadow-sm transition active:scale-90 ${getKeyShapeClass()}`}
                    style={{
                      backgroundColor: isShifted ? theme.accentColor : theme.keyBgColor,
                      color: isShifted ? '#fff' : theme.keyTextColor
                    }}
                  >
                    ↑
                  </button>

                  {getQwertyRow(3).map((char) => (
                    <button
                      key={char}
                      onClick={(e) => handleQwertyKey(char, e)}
                      className={`flex-1 h-10 flex items-center justify-center font-bold text-sm shadow-sm transition active:scale-90 ${getKeyShapeClass()}`}
                      style={{
                        backgroundColor: theme.keyBgColor,
                        fontSize: `${settings.fontSize}px`
                      }}
                    >
                      {isShifted && currentLanguage === 'en' ? char.toUpperCase() : char}
                    </button>
                  ))}

                  {/* Backspace */}
                  <button
                    onClick={handleBackspace}
                    className={`px-3 h-10 flex items-center justify-center font-bold shadow-sm transition active:scale-90 ${getKeyShapeClass()}`}
                    style={{ backgroundColor: theme.keyBgColor }}
                  >
                    <Delete className="w-4 h-4" />
                  </button>
                </div>
              </>
            ) : settings.activeKoreanLayout === 'cheonjiin' ? (
              /* 3B. CHEONJIIN Layout Grid (천지인) */
              <div className="cheonjiin-layout grid grid-cols-3 gap-1 flex-1">
                {/* Row 1: Vowels */}
                <button
                  onClick={() => { triggerVibration(); processCheonjiinVowelInput('ㅣ'); }}
                  className={`h-[34px] flex items-center justify-center font-extrabold text-base shadow-sm active:scale-95 ${getKeyShapeClass()}`}
                  style={{ backgroundColor: theme.keyBgColor }}
                >
                  ㅣ
                </button>
                <button
                  onClick={() => { triggerVibration(); processCheonjiinVowelInput('·'); }}
                  className={`h-[34px] flex items-center justify-center font-extrabold text-lg shadow-sm active:scale-95 ${getKeyShapeClass()}`}
                  style={{ backgroundColor: theme.keyBgColor }}
                >
                  ·
                </button>
                <button
                  onClick={() => { triggerVibration(); processCheonjiinVowelInput('ㅡ'); }}
                  className={`h-[34px] flex items-center justify-center font-extrabold text-base shadow-sm active:scale-95 ${getKeyShapeClass()}`}
                  style={{ backgroundColor: theme.keyBgColor }}
                >
                  ㅡ
                </button>

                {/* Row 2: ㄱㅋ, ㄴㄹ, ㄷㅌ */}
                <button
                  onClick={() => { triggerVibration(); processCheonjiinConsonantInput(['ㄱ', 'ㅋ', 'ㄲ']); }}
                  className={`h-[34px] flex flex-col items-center justify-center shadow-sm active:scale-95 ${getKeyShapeClass()}`}
                  style={{ backgroundColor: theme.keyBgColor }}
                >
                  <span className="font-bold text-sm">ㄱㅋ</span>
                  <span className="text-[9px] opacity-40">ㄲ</span>
                </button>
                <button
                  onClick={() => { triggerVibration(); processCheonjiinConsonantInput(['ㄴ', 'ㄹ']); }}
                  className={`h-[34px] flex flex-col items-center justify-center font-bold text-sm shadow-sm active:scale-95 ${getKeyShapeClass()}`}
                  style={{ backgroundColor: theme.keyBgColor }}
                >
                  ㄴㄹ
                </button>
                <button
                  onClick={() => { triggerVibration(); processCheonjiinConsonantInput(['ㄷ', 'ㅌ', 'ㄸ']); }}
                  className={`h-[34px] flex flex-col items-center justify-center shadow-sm active:scale-95 ${getKeyShapeClass()}`}
                  style={{ backgroundColor: theme.keyBgColor }}
                >
                  <span className="font-bold text-sm">ㄷㅌ</span>
                  <span className="text-[9px] opacity-40">ㄸ</span>
                </button>

                {/* Row 3: ㅂㅍ, ㅅㅎ, ㅈㅊ */}
                <button
                  onClick={() => { triggerVibration(); processCheonjiinConsonantInput(['ㅂ', 'ㅍ', 'ㅃ']); }}
                  className={`h-[34px] flex flex-col items-center justify-center shadow-sm active:scale-95 ${getKeyShapeClass()}`}
                  style={{ backgroundColor: theme.keyBgColor }}
                >
                  <span className="font-bold text-sm">ㅂㅍ</span>
                  <span className="text-[9px] opacity-40">ㅃ</span>
                </button>
                <button
                  onClick={() => { triggerVibration(); processCheonjiinConsonantInput(['ㅅ', 'ㅎ', 'ㅆ']); }}
                  className={`h-[34px] flex flex-col items-center justify-center shadow-sm active:scale-95 ${getKeyShapeClass()}`}
                  style={{ backgroundColor: theme.keyBgColor }}
                >
                  <span className="font-bold text-sm">ㅅㅎ</span>
                  <span className="text-[9px] opacity-40">ㅆ</span>
                </button>
                <button
                  onClick={() => { triggerVibration(); processCheonjiinConsonantInput(['ㅈ', 'ㅊ', 'ㅉ']); }}
                  className={`h-[34px] flex flex-col items-center justify-center shadow-sm active:scale-95 ${getKeyShapeClass()}`}
                  style={{ backgroundColor: theme.keyBgColor }}
                >
                  <span className="font-bold text-sm">ㅈㅊ</span>
                  <span className="text-[9px] opacity-40">ㅉ</span>
                </button>

                {/* Row 4: 획추가, ㅇㅁ, 쌍자음 */}
                <button
                  onClick={handleCheonjiinStrokeAddition}
                  className={`h-[34px] flex items-center justify-center font-bold text-xs shadow-sm active:scale-95 ${getKeyShapeClass()}`}
                  style={{ backgroundColor: theme.keyBgColor, color: theme.accentColor }}
                >
                  획추가
                </button>
                <button
                  onClick={() => { triggerVibration(); processCheonjiinConsonantInput(['ㅇ', 'ㅁ']); }}
                  className={`h-[34px] flex flex-col items-center justify-center font-bold text-sm shadow-sm active:scale-95 ${getKeyShapeClass()}`}
                  style={{ backgroundColor: theme.keyBgColor }}
                >
                  ㅇㅁ
                </button>
                <button
                  onClick={handleCheonjiinDoubleConsonant}
                  className={`h-[34px] flex items-center justify-center font-bold text-xs shadow-sm active:scale-95 ${getKeyShapeClass()}`}
                  style={{ backgroundColor: theme.keyBgColor, color: theme.accentColor }}
                >
                  쌍자음
                </button>
              </div>
            ) : settings.activeKoreanLayout === 'naratgul' ? (
              /* 3C. NARATGUL Layout Grid (나랏글) */
              <div className="naratgul-layout grid grid-cols-5 gap-1 flex-1">
                {/* Consonants Row 1 */}
                <button
                  onClick={() => handleNaratgulKey('ㄱ')}
                  className={`h-9 flex items-center justify-center font-bold text-sm shadow-sm active:scale-95 ${getKeyShapeClass()}`}
                  style={{ backgroundColor: theme.keyBgColor }}
                >
                  ㄱ
                </button>
                <button
                  onClick={() => handleNaratgulKey('ㄴ')}
                  className={`h-9 flex items-center justify-center font-bold text-sm shadow-sm active:scale-95 ${getKeyShapeClass()}`}
                  style={{ backgroundColor: theme.keyBgColor }}
                >
                  ㄴ
                </button>
                <button
                  onClick={() => handleNaratgulKey('ㄷ')}
                  className={`h-9 flex items-center justify-center font-bold text-sm shadow-sm active:scale-95 ${getKeyShapeClass()}`}
                  style={{ backgroundColor: theme.keyBgColor }}
                >
                  ㄷ
                </button>
                {/* Vowels Row 1 */}
                <button
                  onClick={() => handleNaratgulKey('ㅏ')}
                  className={`h-9 flex items-center justify-center font-bold text-sm shadow-sm active:scale-95 ${getKeyShapeClass()}`}
                  style={{ backgroundColor: theme.keyBgColor }}
                >
                  ㅏ
                </button>
                <button
                  onClick={() => handleNaratgulKey('ㅓ')}
                  className={`h-9 flex items-center justify-center font-bold text-sm shadow-sm active:scale-95 ${getKeyShapeClass()}`}
                  style={{ backgroundColor: theme.keyBgColor }}
                >
                  ㅓ
                </button>

                {/* Row 2 */}
                <button
                  onClick={() => handleNaratgulKey('ㄹ')}
                  className={`h-9 flex items-center justify-center font-bold text-sm shadow-sm active:scale-95 ${getKeyShapeClass()}`}
                  style={{ backgroundColor: theme.keyBgColor }}
                >
                  ㄹ
                </button>
                <button
                  onClick={() => handleNaratgulKey('ㅁ')}
                  className={`h-9 flex items-center justify-center font-bold text-sm shadow-sm active:scale-95 ${getKeyShapeClass()}`}
                  style={{ backgroundColor: theme.keyBgColor }}
                >
                  ㅁ
                </button>
                <button
                  onClick={() => handleNaratgulKey('ㅅ')}
                  className={`h-9 flex items-center justify-center font-bold text-sm shadow-sm active:scale-95 ${getKeyShapeClass()}`}
                  style={{ backgroundColor: theme.keyBgColor }}
                >
                  ㅅ
                </button>
                <button
                  onClick={() => handleNaratgulKey('ㅗ')}
                  className={`h-9 flex items-center justify-center font-bold text-sm shadow-sm active:scale-95 ${getKeyShapeClass()}`}
                  style={{ backgroundColor: theme.keyBgColor }}
                >
                  ㅗ
                </button>
                <button
                  onClick={() => handleNaratgulKey('ㅜ')}
                  className={`h-9 flex items-center justify-center font-bold text-sm shadow-sm active:scale-95 ${getKeyShapeClass()}`}
                  style={{ backgroundColor: theme.keyBgColor }}
                >
                  ㅜ
                </button>

                {/* Row 3 */}
                <button
                  onClick={() => handleNaratgulKey('ㅇ')}
                  className={`h-9 flex items-center justify-center font-bold text-sm shadow-sm active:scale-95 ${getKeyShapeClass()}`}
                  style={{ backgroundColor: theme.keyBgColor }}
                >
                  ㅇ
                </button>
                <button
                  onClick={() => handleNaratgulKey('ㅈ')}
                  className={`h-9 flex items-center justify-center font-bold text-sm shadow-sm active:scale-95 ${getKeyShapeClass()}`}
                  style={{ backgroundColor: theme.keyBgColor }}
                >
                  ㅈ
                </button>
                <button
                  onClick={() => handleNaratgulKey('ㅊ')}
                  className={`h-9 flex items-center justify-center font-bold text-sm shadow-sm active:scale-95 ${getKeyShapeClass()}`}
                  style={{ backgroundColor: theme.keyBgColor }}
                >
                  ㅊ
                </button>
                <button
                  onClick={() => handleNaratgulKey('ㅡ')}
                  className={`h-9 flex items-center justify-center font-bold text-sm shadow-sm active:scale-95 ${getKeyShapeClass()}`}
                  style={{ backgroundColor: theme.keyBgColor }}
                >
                  ㅡ
                </button>
                <button
                  onClick={() => handleNaratgulKey('ㅣ')}
                  className={`h-9 flex items-center justify-center font-bold text-sm shadow-sm active:scale-95 ${getKeyShapeClass()}`}
                  style={{ backgroundColor: theme.keyBgColor }}
                >
                  ㅣ
                </button>

                {/* Row 4: Controls */}
                <button
                  onClick={handleNaratgulStrokeAddition}
                  className={`h-9 flex items-center justify-center font-bold text-xs shadow-sm active:scale-95 ${getKeyShapeClass()} col-span-2`}
                  style={{ backgroundColor: theme.keyBgColor, color: theme.accentColor }}
                >
                  획추가 (+1 Stroke)
                </button>
                <button
                  onClick={handleNaratgulDoubleConsonant}
                  className={`h-9 flex items-center justify-center font-bold text-xs shadow-sm active:scale-95 ${getKeyShapeClass()}`}
                  style={{ backgroundColor: theme.keyBgColor, color: theme.accentColor }}
                >
                  쌍자음
                </button>
                <button
                  onClick={handleBackspace}
                  className={`h-9 flex items-center justify-center font-bold text-sm shadow-sm active:scale-95 ${getKeyShapeClass()} col-span-2`}
                  style={{ backgroundColor: theme.keyBgColor }}
                >
                  <Delete className="w-4 h-4" />
                </button>
              </div>
            ) : (
              /* 3E. GEOMJIGEUL Layout Grid (검지글) */
              <div className="geomjigeul-layout grid grid-cols-7 gap-1 flex-1 select-none touch-none">
                {/* Row 1 of Geomjigeul */}
                <button
                  onClick={() => { triggerVibration(); alert("앱실행 기능입니다."); }}
                  className={`h-[34px] flex items-center justify-center font-bold text-[10px] shadow-sm active:scale-95 ${getKeyShapeClass()}`}
                  style={{ backgroundColor: theme.keyBgColor, opacity: 0.8 }}
                >
                  앱실행
                </button>
                <button
                  {...getDragHandlers('ㄱ')}
                  className={`relative h-[34px] flex flex-col items-center justify-center font-bold text-sm shadow-sm active:scale-95 ${getKeyShapeClass()} touch-none`}
                  style={{ backgroundColor: theme.keyBgColor }}
                >
                  ㄱ
                  {renderDragIndicators('ㄱ')}
                </button>
                <button
                  onClick={() => handleGeomjigeulKey('ㄴ')}
                  className={`h-[34px] flex items-center justify-center font-bold text-sm shadow-sm active:scale-95 ${getKeyShapeClass()}`}
                  style={{ backgroundColor: theme.keyBgColor }}
                >
                  ㄴ
                </button>
                <button
                  {...getDragHandlers('ㄷ')}
                  className={`relative h-[34px] flex flex-col items-center justify-center font-bold text-sm shadow-sm active:scale-95 ${getKeyShapeClass()} touch-none`}
                  style={{ backgroundColor: theme.keyBgColor }}
                >
                  ㄷ
                  {renderDragIndicators('ㄷ')}
                </button>
                <button
                  onClick={() => handleGeomjigeulKey('ㅗ')}
                  className={`h-[34px] flex flex-col items-center justify-center font-bold text-sm shadow-sm active:scale-95 ${getKeyShapeClass()}`}
                  style={{ backgroundColor: theme.keyBgColor }}
                >
                  <span>ㅗ</span>
                  <span className="text-[9px] opacity-40">ㅛ</span>
                </button>
                <button
                  onClick={() => handleGeomjigeulKey('ㅏ')}
                  className={`h-[34px] flex flex-col items-center justify-center font-bold text-sm shadow-sm active:scale-95 ${getKeyShapeClass()}`}
                  style={{ backgroundColor: theme.keyBgColor }}
                >
                  <span>ㅏ</span>
                  <span className="text-[9px] opacity-40">야</span>
                </button>
                <button
                  onClick={() => { triggerVibration(); setCurrentLanguage('en'); }}
                  className={`h-[34px] flex items-center justify-center font-bold text-[11px] shadow-sm active:scale-95 ${getKeyShapeClass()}`}
                  style={{ backgroundColor: theme.keyBgColor }}
                >
                  영
                </button>

                {/* Row 2 of Geomjigeul */}
                <button
                  onClick={() => { triggerVibration(); setActiveTab('emoji'); }}
                  className={`h-[34px] flex items-center justify-center font-bold text-[11px] shadow-sm active:scale-95 ${getKeyShapeClass()}`}
                  style={{ backgroundColor: theme.keyBgColor, opacity: 0.8 }}
                >
                  클립
                </button>
                <button
                  onClick={() => handleGeomjigeulKey('ㄹ')}
                  className={`h-[34px] flex items-center justify-center font-bold text-sm shadow-sm active:scale-95 ${getKeyShapeClass()}`}
                  style={{ backgroundColor: theme.keyBgColor }}
                >
                  ㄹ
                </button>
                <button
                  onClick={() => handleGeomjigeulKey('ㅁ')}
                  className={`h-[34px] flex items-center justify-center font-bold text-sm shadow-sm active:scale-95 ${getKeyShapeClass()}`}
                  style={{ backgroundColor: theme.keyBgColor }}
                >
                  ㅁ
                </button>
                <button
                  {...getDragHandlers('ㅂ')}
                  className={`relative h-[34px] flex flex-col items-center justify-center font-bold text-sm shadow-sm active:scale-95 ${getKeyShapeClass()} touch-none`}
                  style={{ backgroundColor: theme.keyBgColor }}
                >
                  ㅂ
                  {renderDragIndicators('ㅂ')}
                </button>
                <button
                  onClick={() => handleGeomjigeulKey('ㅡ')}
                  className={`h-[34px] flex flex-col items-center justify-center font-bold text-sm shadow-sm active:scale-95 ${getKeyShapeClass()}`}
                  style={{ backgroundColor: theme.keyBgColor }}
                >
                  <span>ㅡ</span>
                  <span className="text-[9px] opacity-40">ㅢ</span>
                </button>
                <button
                  onClick={() => handleGeomjigeulKey('ㅣ')}
                  className={`h-[34px] flex items-center justify-center font-bold text-sm shadow-sm active:scale-95 ${getKeyShapeClass()}`}
                  style={{ backgroundColor: theme.keyBgColor }}
                >
                  ㅣ
                </button>
                <button
                  onClick={() => { triggerVibration(); setActiveTab('emoji'); }}
                  className={`h-[34px] flex items-center justify-center font-bold text-[11px] shadow-sm active:scale-95 ${getKeyShapeClass()}`}
                  style={{ backgroundColor: theme.keyBgColor }}
                >
                  123
                </button>

                {/* Row 3 of Geomjigeul */}
                <div className="h-[34px]"></div>
                <button
                  {...getDragHandlers('ㅅ')}
                  className={`relative h-[34px] flex flex-col items-center justify-center font-bold text-sm shadow-sm active:scale-95 ${getKeyShapeClass()} touch-none`}
                  style={{ backgroundColor: theme.keyBgColor }}
                >
                  ㅅ
                  {renderDragIndicators('ㅅ')}
                </button>
                <button
                  {...getDragHandlers('ㅇ')}
                  className={`relative h-[34px] flex flex-col items-center justify-center font-bold text-sm shadow-sm active:scale-95 ${getKeyShapeClass()} touch-none`}
                  style={{ backgroundColor: theme.keyBgColor }}
                >
                  ㅇ
                  {renderDragIndicators('ㅇ')}
                </button>
                <button
                  {...getDragHandlers('ㅈ')}
                  className={`relative h-[34px] flex flex-col items-center justify-center font-bold text-sm shadow-sm active:scale-95 ${getKeyShapeClass()} touch-none`}
                  style={{ backgroundColor: theme.keyBgColor }}
                >
                  ㅈ
                  {renderDragIndicators('ㅈ')}
                </button>
                <button
                  onClick={() => handleGeomjigeulKey('ㅜ')}
                  className={`h-[34px] flex flex-col items-center justify-center font-bold text-sm shadow-sm active:scale-95 ${getKeyShapeClass()}`}
                  style={{ backgroundColor: theme.keyBgColor }}
                >
                  <span>ㅜ</span>
                  <span className="text-[9px] opacity-40">ㅠ</span>
                </button>
                <button
                  onClick={() => handleGeomjigeulKey('ㅓ')}
                  className={`h-[34px] flex flex-col items-center justify-center font-bold text-sm shadow-sm active:scale-95 ${getKeyShapeClass()}`}
                  style={{ backgroundColor: theme.keyBgColor }}
                >
                  <span>ㅓ</span>
                  <span className="text-[9px] opacity-40">ㅕ</span>
                </button>
                <button
                  onClick={handleBackspace}
                  className={`h-[34px] flex items-center justify-center font-bold text-sm shadow-sm active:scale-95 ${getKeyShapeClass()}`}
                  style={{ backgroundColor: theme.keyBgColor }}
                >
                  <Delete className="w-4 h-4" />
                </button>

                {/* Row 4 of Geomjigeul */}
                <button
                  onClick={() => { triggerVibration(); setShowLayoutMenu(true); }}
                  className={`h-[34px] flex items-center justify-center font-bold text-[11px] shadow-sm active:scale-95 ${getKeyShapeClass()}`}
                  style={{ backgroundColor: theme.keyBgColor, opacity: 0.9 }}
                >
                  배열
                </button>
                <button
                  onClick={handleGeomjigeulStrokeAddition}
                  className={`h-[34px] flex items-center justify-center font-bold text-xs shadow-sm active:scale-95 ${getKeyShapeClass()}`}
                  style={{ backgroundColor: theme.keyBgColor, color: theme.accentColor }}
                >
                  획추가 *
                </button>
                <button
                  onClick={() => { triggerVibration(); insertText('.'); }}
                  className={`h-[34px] flex items-center justify-center font-bold text-xs shadow-sm active:scale-95 ${getKeyShapeClass()}`}
                  style={{ backgroundColor: theme.keyBgColor }}
                >
                  .,!?
                </button>
                <button
                  onClick={handleGeomjigeulDoubleConsonant}
                  className={`h-[34px] flex items-center justify-center font-bold text-xs shadow-sm active:scale-95 ${getKeyShapeClass()}`}
                  style={{ backgroundColor: theme.keyBgColor, color: theme.accentColor }}
                >
                  쌍자음 #
                </button>
                <button
                  onClick={handleSpace}
                  className={`h-[34px] flex items-center justify-center font-bold text-xs shadow-sm active:scale-95 ${getKeyShapeClass()} col-span-2`}
                  style={{ backgroundColor: theme.keyBgColor }}
                >
                  Space
                </button>
                <button
                  onClick={handleEnter}
                  className={`h-[34px] flex items-center justify-center font-bold text-xs shadow-sm active:scale-95 ${getKeyShapeClass()}`}
                  style={{ backgroundColor: theme.keyBgColor, color: theme.accentColor }}
                >
                  Enter
                </button>
              </div>
            )}

            {/* 3D. Common Bottom Toolbar Key Row */}
            <div className="bottom-toolbar-row flex justify-between w-full gap-1">
              {/* Special Tab Toggle !?☺ */}
              <button
                onClick={() => { triggerVibration(); setActiveTab('emoji'); }}
                className={`px-3 h-10 flex items-center justify-center font-bold text-xs shadow-sm active:scale-95 ${getKeyShapeClass()}`}
                style={{ backgroundColor: theme.keyBgColor }}
              >
                !?☺
              </button>

              {/* Quick Layout Switcher Button */}
              <button
                onClick={() => { triggerVibration(); setShowLayoutMenu(true); }}
                className={`px-2.5 h-10 flex items-center justify-center font-bold text-xs shadow-sm active:scale-95 ${getKeyShapeClass()}`}
                style={{ backgroundColor: theme.keyBgColor, color: theme.accentColor }}
                title="배열 변경"
              >
                배열
              </button>

              {/* Language Switch Globe */}
              {settings.languages.ko && settings.languages.en && (
                <button
                  onClick={() => { triggerVibration(); setCurrentLanguage(currentLanguage === 'ko' ? 'en' : 'ko'); }}
                  className={`px-3.5 h-10 flex items-center justify-center font-bold text-xs shadow-sm active:scale-95 ${getKeyShapeClass()}`}
                  style={{ backgroundColor: theme.keyBgColor, color: theme.accentColor }}
                >
                  <Globe className="w-4 h-4 mr-0.5" />
                  <span>{currentLanguage === 'ko' ? '한' : 'EN'}</span>
                </button>
              )}

              {/* Space Key */}
              <button
                onClick={handleSpace}
                className={`flex-1 h-10 flex items-center justify-center text-xs font-semibold shadow-sm active:scale-95 ${getKeyShapeClass()}`}
                style={{ backgroundColor: theme.keyBgColor }}
              >
                {currentLanguage === 'ko' ? '스페이스' : 'Space'}
              </button>

              {/* Microphone Key */}
              <button
                onClick={switchToVoiceTab}
                className={`px-3.5 h-10 flex items-center justify-center shadow-sm active:scale-95 ${getKeyShapeClass()}`}
                style={{ backgroundColor: theme.keyBgColor }}
              >
                <Mic className="w-4 h-4 text-sky-500" />
              </button>

              {/* Auto-Correction [수정] button */}
              <button
                onClick={() => { triggerVibration(); applySentenceCorrection(); }}
                disabled={!correctionCandidate}
                className={`px-3 h-10 flex items-center justify-center text-[10px] font-extrabold shadow-sm active:scale-95 ${getKeyShapeClass()}`}
                style={{
                  backgroundColor: correctionCandidate ? 'rgba(245, 158, 11, 0.2)' : theme.keyBgColor,
                  color: correctionCandidate ? '#F59E0B' : 'gray',
                  border: correctionCandidate ? '1px solid #F59E0B' : '1px solid transparent'
                }}
              >
                수정
              </button>

              {/* Enter / Action Button */}
              <button
                onClick={handleEnter}
                className={`px-5 h-10 flex items-center justify-center text-xs font-bold text-white shadow-md active:scale-95 ${getKeyShapeClass()}`}
                style={{ backgroundColor: theme.accentColor }}
              >
                Enter
              </button>
            </div>
          </div>
        )}

        {/* TAB 2: Emoji & Special characters Pane */}
        {activeTab === 'emoji' && (
          <div className="flex flex-col h-full justify-between gap-1.5 p-1">
            {/* Category tabs */}
            <div className="flex items-center gap-1 border-b border-gray-700/20 pb-1">
              <button
                onClick={() => setEmojiCategory('faces')}
                className={`flex-1 py-1 text-center text-xs rounded transition ${emojiCategory === 'faces' ? 'bg-sky-500/20 text-sky-500 font-bold' : 'opacity-60'}`}
              >
                ☺ 얼굴
              </button>
              <button
                onClick={() => setEmojiCategory('animals')}
                className={`flex-1 py-1 text-center text-xs rounded transition ${emojiCategory === 'animals' ? 'bg-sky-500/20 text-sky-500 font-bold' : 'opacity-60'}`}
              >
                🐱 동물
              </button>
              <button
                onClick={() => setEmojiCategory('objects')}
                className={`flex-1 py-1 text-center text-xs rounded transition ${emojiCategory === 'objects' ? 'bg-sky-500/20 text-sky-500 font-bold' : 'opacity-60'}`}
              >
                ✈ 사물
              </button>
              <button
                onClick={() => setEmojiCategory('symbols')}
                className={`flex-1 py-1 text-center text-xs rounded transition ${emojiCategory === 'symbols' ? 'bg-sky-500/20 text-sky-500 font-bold' : 'opacity-60'}`}
              >
                !#% 기호
              </button>
            </div>

            {/* Emoji Grid list */}
            <div className="grid grid-cols-7 gap-1 overflow-y-auto flex-1 min-h-[40px] py-1 text-center text-lg content-start">
              {emojiCategory === 'faces' && ['😀', '😃', '😄', '😁', '😆', '😅', '😂', '🤣', '😊', '😇', '🙂', '🙃', '😉', '😌', '😍', '🥰', '😘', '😗', '😙', '😚', '😋', '😛', '😝', '😜', '🤪', '🤨', '🧐', '🤓'].map(em => (
                <button key={em} onClick={() => insertText(em)} className="p-1 hover:bg-gray-500/10 rounded active:scale-90">{em}</button>
              ))}
              {emojiCategory === 'animals' && ['🐶', '🐱', '🐭', '🐹', '🐰', '🦊', '🐻', '🐼', '🐨', '🐯', '🦁', '🐮', '🐷', '🐸', '🐵', '🐔', '🐧', '🐦', '🍎', '🍐', '🍊', '🍋', '🍌', '🍉', '🍇', '🍓', '🍒', '🍑'].map(em => (
                <button key={em} onClick={() => insertText(em)} className="p-1 hover:bg-gray-500/10 rounded active:scale-90">{em}</button>
              ))}
              {emojiCategory === 'objects' && ['🚗', '🚕', '🚙', '🚌', '🚎', '🏎', '🚓', '🚑', '🚒', '🚐', '🚚', '🚛', '🚜', '🚲', '🛴', '🏍', '🛵', '🛫', '✈', '🚁', '🛰', '🚀', '🛸', '⏰', '📱', '💻', '📷', '🎥'].map(em => (
                <button key={em} onClick={() => insertText(em)} className="p-1 hover:bg-gray-500/10 rounded active:scale-90">{em}</button>
              ))}
              {emojiCategory === 'symbols' && ['!', '@', '#', '$', '%', '^', '&', '*', '(', ')', '_', '+', '=', '-', '{', '}', '[', ']', ';', ':', '"', "'", '<', '>', ',', '.', '?', '/'].map(em => (
                <button key={em} onClick={() => insertText(em)} className="p-1 hover:bg-gray-500/10 rounded active:scale-90">{em}</button>
              ))}
            </div>

            {/* Bottom Actions */}
            <div className="flex items-center gap-1.5 mt-auto">
              <button
                onClick={() => { triggerVibration(); setActiveTab('keyboard'); }}
                className={`flex-1 py-1.5 text-xs text-center font-bold bg-gray-500/20 rounded active:scale-95`}
              >
                키보드로 돌아가기
              </button>
              <button
                onClick={handleBackspace}
                className="px-4 py-1.5 bg-gray-500/20 rounded active:scale-95"
              >
                <Delete className="w-4 h-4 mx-auto" />
              </button>
            </div>
          </div>
        )}

        {/* TAB 3: Voice input screen */}
        {activeTab === 'voice' && (
          <div className="flex flex-col h-full justify-between items-center p-2">
            <div className="flex flex-col items-center gap-1 w-full flex-1 justify-center">
              
              {/* Mic Icon & Wave Pulsing (Microphone interface) */}
              <div className="relative flex items-center justify-center">
                {isListening && (
                  <motion.div
                    animate={{ scale: [1, 1.4, 1] }}
                    transition={{ repeat: Infinity, duration: 1.5 }}
                    className="absolute w-12 h-12 bg-sky-500/20 rounded-full"
                  />
                )}
                <button
                  onClick={toggleVoiceListening}
                  className={`w-10 h-10 rounded-full flex items-center justify-center transition shadow-md ${isListening ? 'bg-red-500 text-white' : 'bg-sky-500 text-white'}`}
                >
                  <Mic className="w-5 h-5" />
                </button>
              </div>

              <span className="text-[10px] font-bold mt-1" style={{ color: isListening ? '#EF4444' : '#0EA5E9' }}>
                {isListening ? '말씀하세요... (음성 인식 중)' : '마이크를 눌러 말하기'}
              </span>

              {/* Transcription Result display */}
              <div 
                className="w-full text-center px-4 py-1.5 rounded text-xs font-semibold h-[42px] overflow-y-auto mt-1 max-w-[240px]"
                style={{ backgroundColor: theme.isDark ? '#1C1C1F' : '#E9ECEF' }}
              >
                {voiceTranscript ? (
                  <span className={theme.isDark ? 'text-white font-bold' : 'text-slate-900 font-bold'}>{voiceTranscript}</span>
                ) : (
                  <span className="text-gray-400 italic">아래 마이크를 켠 뒤 이야기를 시작하세요.</span>
                )}
              </div>
            </div>

            {/* Voice Input Bottom Options */}
            <div className="flex items-center gap-2 w-full mt-auto">
              <button
                onClick={() => { triggerVibration(); setIsListening(false); setActiveTab('keyboard'); }}
                className="flex-1 py-1.5 text-xs font-bold text-center bg-gray-500/10 border border-gray-500/20 rounded active:scale-95"
              >
                취소
              </button>
              
              <button
                onClick={commitVoiceInput}
                disabled={!voiceTranscript || voiceTranscript === '인식 중...'}
                className="flex-1 py-1.5 text-xs font-bold text-center text-white rounded active:scale-95 disabled:opacity-40"
                style={{ backgroundColor: theme.accentColor }}
              >
                텍스트 삽입
              </button>
            </div>
          </div>
        )}
      </div>

      {/* Quick Keyboard Layout Switcher Menu */}
      {showLayoutMenu && (
        <div className="absolute inset-0 bg-slate-900/95 z-50 flex flex-col justify-between p-3.5 rounded-xl border border-slate-700/50">
          <div className="flex items-center justify-between border-b border-slate-700/50 pb-2">
            <span className="text-white text-xs font-bold font-sans flex items-center gap-1">
              ⌨️ 키보드 배열 변경
            </span>
            <button
              onClick={() => setShowLayoutMenu(false)}
              className="text-gray-400 hover:text-white text-[10px] font-bold px-2 py-1 rounded bg-slate-800 border border-slate-700"
            >
              닫기
            </button>
          </div>

          <div className="grid grid-cols-2 gap-2 my-auto">
            {[
              { id: 'qwerty', name: '두벌식 (QWERTY)' },
              { id: 'cheonjiin', name: '천지인 (Cheonjiin)' },
              { id: 'naratgul', name: '나랏글 (Naratgul)' },
              { id: 'geomjigeul', name: '검지글 (Geomjigeul)' }
            ].map((lay) => (
              <button
                key={lay.id}
                onClick={() => {
                  triggerVibration();
                  handleLayoutChange(lay.id as KoreanLayout);
                }}
                className={`py-3 text-xs font-bold rounded-lg border transition duration-150 ${
                  settings.activeKoreanLayout === lay.id
                    ? 'bg-sky-500 border-sky-400 text-white shadow-md font-extrabold scale-[1.02]'
                    : 'bg-slate-800 border-slate-700 text-slate-300 hover:bg-slate-700 hover:text-white'
                }`}
              >
                {lay.name}
              </button>
            ))}
          </div>

          <div className="text-[10px] text-slate-500 text-center font-medium border-t border-slate-850 pt-2">
            시스템 설정에 들어가지 않고 키보드 배열을 바로 전환합니다.
          </div>
        </div>
      )}

      {/* Floating auto-correct Toast notification */}
      <AnimatePresence>
        {showCorrectionToast && (
          <motion.div
            initial={{ opacity: 0, y: 15 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, y: -15 }}
            className="absolute -top-12 left-1/2 transform -translate-x-1/2 bg-amber-500 text-white text-[11px] font-bold px-3 py-1.5 rounded-full shadow-lg flex items-center gap-1 z-50 pointer-events-none"
          >
            <Check className="w-3.5 h-3.5" />
            <span>오타가 자동으로 수정되었습니다!</span>
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  );
}
