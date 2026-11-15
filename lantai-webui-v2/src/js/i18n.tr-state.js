(function () {
  var A = window.LANTAI_I18N_DICT || (window.LANTAI_I18N_DICT = {});
  var K = ["空闲超过 {v0} 分钟，进程已回收", "空闲超过 {v0} 分钟，堆内存已压缩", "已释放内存"];
  var V = {
    "ar": ["بعد أكثر من {v0} دقيقة من الخمول، تم استرداد العملية", "بعد أكثر من {v0} دقيقة من الخمول، تم ضغط ذاكرة الكومة", "تم تحرير الذاكرة"],
    "bg": ["Неактивност над {v0} минути, процесът е освободен", "Неактивност над {v0} минути, паметта на хийпа е компресирана", "Паметта е освободена"],
    "ca": ["Inactiu més de {v0} minuts, procés reciclat", "Inactiu més de {v0} minuts, memòria del heap compactada", "Memòria alliberada"],
    "cs": ["Nečinnost přes {v0} minut, proces uvolněn", "Nečinnost přes {v0} minut, paměť haldy zkomprimována", "Paměť uvolněna"],
    "da": ["Inaktiv i over {v0} minutter, processen er frigivet", "Inaktiv i over {v0} minutter, heap-hukommelsen er komprimeret", "Hukommelse frigivet"],
    "de": ["Über {v0} Minuten inaktiv, Prozess freigegeben", "Über {v0} Minuten inaktiv, Heap-Speicher komprimiert", "Speicher freigegeben"],
    "el": ["Αδράνεια άνω των {v0} λεπτών, η διεργασία ανακυκλώθηκε", "Αδράνεια άνω των {v0} λεπτών, η μνήμη σωρού συμπιέστηκε", "Η μνήμη απελευθερώθηκε"],
    "es": ["Inactivo más de {v0} minutos, proceso reciclado", "Inactivo más de {v0} minutos, memoria del heap compactada", "Memoria liberada"],
    "fr": ["Inactif depuis plus de {v0} minutes, processus libéré", "Inactif depuis plus de {v0} minutes, mémoire du tas compactée", "Mémoire libérée"],
    "it": ["Inattivo da più di {v0} minuti, processo rilasciato", "Inattivo da più di {v0} minuti, memoria heap compattata", "Memoria liberata"],
    "ja": ["{v0} 分以上アイドル状態のため、プロセスを解放しました", "{v0} 分以上アイドル状態のため、ヒープメモリを圧縮しました", "メモリ解放済み"],
    "ko": ["{v0}분 이상 유휴 상태여서 프로세스를 회수했습니다", "{v0}분 이상 유휴 상태여서 힙 메모리를 압축했습니다", "메모리 해제됨"],
    "pt-BR": ["Inativo por mais de {v0} minutos, processo liberado", "Inativo por mais de {v0} minutos, memória heap compactada", "Memória liberada"],
    "ru": ["Простой более {v0} минут, процесс освобождён", "Простой более {v0} минут, память кучи сжата", "Память освобождена"],
    "sl": ["Nedejavno več kot {v0} minut, proces je bil sproščen", "Nedejavno več kot {v0} minut, kopični pomnilnik je stisnjen", "Pomnilnik sproščen"],
    "sv": ["Inaktiv i mer än {v0} minuter, processen återvanns", "Inaktiv i mer än {v0} minuter, heapminnet komprimerat", "Minne frigjort"],
    "ta": ["{v0} நிமிடங்களுக்கு மேல் செயலற்றது, செயல்முறை மீட்கப்பட்டது", "{v0} நிமிடங்களுக்கு மேல் செயலற்றது, ஹீப் நினைவகம் சுருக்கப்பட்டது", "நினைவகம் விடுவிக்கப்பட்டது"],
    "th": ["ไม่ได้ใช้งานเกิน {v0} นาที กระบวนการถูกเรียกคืน", "ไม่ได้ใช้งานเกิน {v0} นาที หน่วยความจำฮีปถูกบีบอัด", "ปล่อยหน่วยความจำแล้ว"],
    "ti": ["ልዕሊ {v0} ደቓይቕ ዘይተጠቕመ፣ ሂደት ተመሊሱ", "ልዕሊ {v0} ደቓይቕ ዘይተጠቕመ፣ ሂፕ ትሕዝቶ ተጨመቐ", "ትሕዝቶ ተለቂቑ"],
    "tr": ["{v0} dakikadan fazla boşta, süreç geri alındı", "{v0} dakikadan fazla boşta, yığın belleği sıkıştırıldı", "Bellek serbest bırakıldı"],
    "uk": ["Простій понад {v0} хвилин, процес звільнено", "Простій понад {v0} хвилин, пам'ять купи стиснуто", "Пам'ять звільнено"],
    "vi": ["Không hoạt động hơn {v0} phút, tiến trình đã được thu hồi", "Không hoạt động hơn {v0} phút, bộ nhớ heap đã được nén", "Đã giải phóng bộ nhớ"],
    "zh-Hant": ["空閒超過 {v0} 分鐘，處理程序已回收", "空閒超過 {v0} 分鐘，堆疊記憶體已壓縮", "已釋放記憶體"]
  };
  for (var c in V) { var t = A[c] || (A[c] = {}), a = V[c]; for (var i = 0; i < K.length; i++) t[K[i]] = a[i]; }
})();
