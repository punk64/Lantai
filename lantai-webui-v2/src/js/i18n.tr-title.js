(function () {
  var A = window.LANTAI_I18N_DICT || (window.LANTAI_I18N_DICT = {});
  var K = ["返回顶部","跳转页面、打开书籍、执行操作…"];
  var V = {"zh-Hans":["返回顶部","跳转页面、打开书籍、执行操作…"],"zh-Hant":["返回頂部","跳轉頁面、開啟書籍、執行操作…"],"ja":["トップへ戻る","ページへ移動、書籍を開く、操作を実行…"],"ko":["맨 위로","페이지 이동, 책 열기, 작업 실행…"],"fr":["Retour en haut","Aller à une page, ouvrir un livre, lancer une action…"],"de":["Nach oben","Zu einer Seite springen, Buch öffnen, Aktion ausführen…"],"es":["Volver arriba","Ir a una página, abrir un libro, ejecutar una acción…"],"it":["Torna su","Vai a una pagina, apri un libro, esegui un'azione…"],"pt-BR":["Voltar ao topo","Ir para uma página, abrir um livro, executar uma ação…"],"ru":["Наверх","Перейти на страницу, открыть книгу, выполнить действие…"],"vi":["Về đầu trang","Chuyển trang, mở sách, thực hiện thao tác…"],"en":["Back to top","Jump to pages, open books, run actions…"]};
  for (var c in V) { var t = A[c] || (A[c] = {}), a = V[c]; for (var i = 0; i < K.length; i++) t[K[i]] = a[i]; }
})();
