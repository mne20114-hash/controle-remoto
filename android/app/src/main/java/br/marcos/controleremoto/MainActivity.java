package br.marcos.controleremoto;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;

public class MainActivity extends Activity {
    static final int PORTA = 8765, PORTA_BUSCA = 8766;
    static final int BG = 0xFF12141C, CARD = 0xFF262B3A, AZUL = 0xFF3B6FF5, VERDE = 0xFF2EA86B,
            SUB = 0xFFB0B6C8, AMARELO = 0xFFFFC857, VERMELHO = 0xFF8A2E3B;

    final LinkedBlockingQueue<String> fila = new LinkedBlockingQueue<>();
    final Set<String> encontrados = new HashSet<>();
    volatile Socket sock;
    Thread envio;
    SharedPreferences prefs;
    TextView status;
    LinearLayout listaPcs;
    EditText campoIp, campoPin, digitacao;
    boolean noPad = false, segurando = false, ignorarTexto = false;
    String nomePc = "", anterior = "";
    // voz e assistente
    static final int OUVIR_DITADO = 1, OUVIR_BUSCA = 2, OUVIR_ASSISTENTE = 3;
    static final String MODELO_PADRAO = "gpt-5.4-mini", MODELO_GEMINI = "gemini-3.8-flash";

    static boolean ehGemini(String chave) { return chave.startsWith("AIza"); }

    String modeloAtual(String chave) {
        String m = prefs.getString("modelo", "");
        boolean g = ehGemini(chave);
        if (m.isEmpty() || (g && !m.startsWith("gemini")) || (!g && m.startsWith("gemini"))) return g ? MODELO_GEMINI : MODELO_PADRAO;
        return m;
    }
    final LinkedBlockingQueue<String> respostas = new LinkedBlockingQueue<>();
    final List<String[]> conversa = new ArrayList<>();
    JSONArray historico = new JSONArray();
    TextToSpeech tts;
    boolean noAssistente = false, conversaContinua = false, usarEsforco = true, pensando = false;
    LinearLayout chatLista;
    ScrollView chatScroll;
    TextView assistStatus;

    // ------------------------------------------------------------------ utilitários de tela
    int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    LinearLayout vertical() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }

    LinearLayout horizontal() {
        LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL); return l;
    }

    LinearLayout.LayoutParams mt(int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(top); return p;
    }

    LinearLayout.LayoutParams peso(int margem) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
        p.leftMargin = dp(margem); p.rightMargin = dp(margem); return p;
    }

    TextView texto(String s, float tam, int cor) {
        TextView t = new TextView(this); t.setText(s); t.setTextSize(tam); t.setTextColor(cor); return t;
    }

    GradientDrawable fundo(int cor) {
        GradientDrawable g = new GradientDrawable(); g.setColor(cor); g.setCornerRadius(dp(12)); return g;
    }

    Button botao(String s, int cor, View.OnClickListener c) {
        Button b = new Button(this); b.setText(s); b.setAllCaps(false); b.setTextColor(Color.WHITE);
        b.setTextSize(16); b.setBackground(fundo(cor)); b.setPadding(dp(16), dp(12), dp(16), dp(12));
        b.setStateListAnimator(null); b.setOnClickListener(c); return b;
    }

    EditText campo(String dica, int tipo) {
        EditText e = new EditText(this); e.setHint(dica); e.setInputType(tipo); e.setTextColor(Color.WHITE);
        e.setHintTextColor(0xFF6B7288); e.setBackground(fundo(CARD)); e.setPadding(dp(14), dp(12), dp(14), dp(12));
        e.setSingleLine(true); return e;
    }

    // ------------------------------------------------------------------ ciclo de vida
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("cfg", MODE_PRIVATE);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        tts = new TextToSpeech(this, st -> {
            if (st == TextToSpeech.SUCCESS) tts.setLanguage(new Locale("pt", "BR"));
        });
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            public void onStart(String id) { }
            public void onError(String id) { }
            public void onDone(String id) {
                runOnUiThread(() -> { if (noAssistente && conversaContinua && !pensando) ouvir(OUVIR_ASSISTENTE, "Pode falar..."); });
            }
        });
        mostrarConexao();
        buscar();
    }

    @Override public void onBackPressed() {
        if (noAssistente) mostrarPad();
        else if (noPad) desconectar(); else super.onBackPressed();
    }

    @Override protected void onDestroy() { fechar(); if (tts != null) tts.shutdown(); super.onDestroy(); }

    // ------------------------------------------------------------------ tela de conexão
    void mostrarConexao() {
        noPad = false; segurando = false; noAssistente = false;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        ScrollView sv = new ScrollView(this); sv.setBackgroundColor(BG);
        LinearLayout l = vertical(); l.setPadding(dp(24), dp(32), dp(24), dp(24));
        sv.addView(l);
        TextView t = texto("Controle Remoto", 28, Color.WHITE); t.setTypeface(Typeface.DEFAULT_BOLD); l.addView(t);
        l.addView(texto("Abra o Controle Remoto no notebook.\n"
                + "• Wi-Fi: tablet e notebook na mesma rede.\n"
                + "• Bluetooth: no tablet ative \"Ancoragem Bluetooth\" (Configurações > Conexões > Roteador Wi-Fi e ancoragem) "
                + "e, no notebook, conecte-se ao tablet como \"Ponto de acesso\" (Bluetooth > tablet > Conectar usando > Ponto de acesso).", 15, SUB), mt(10));
        l.addView(botao("Procurar computadores", AZUL, v -> buscar()), mt(20));
        listaPcs = vertical(); l.addView(listaPcs, mt(4));
        l.addView(texto("Ou digite manualmente:", 15, SUB), mt(22));
        campoIp = campo("IP do notebook (ex.: 192.168.0.10)", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        campoIp.setText(prefs.getString("ip", ""));
        l.addView(campoIp, mt(8));
        campoPin = campo("PIN que aparece no notebook", InputType.TYPE_CLASS_NUMBER);
        campoPin.setText(prefs.getString("pin", ""));
        l.addView(campoPin, mt(8));
        l.addView(botao("Conectar", VERDE, v -> conectar(campoIp.getText().toString().trim(), "")), mt(12));
        status = texto("", 15, AMARELO); l.addView(status, mt(16));
        encontrados.clear();
        setContentView(sv);
    }

    void adicionarPc(String nome, String ip) {
        if (noPad || !encontrados.add(ip)) return;
        listaPcs.addView(botao("💻  " + nome + "   (" + ip + ")", CARD, v -> {
            campoIp.setText(ip); conectar(ip, nome);
        }), mt(8));
        status.setText("Toque no computador para conectar.");
    }

    void buscar() {
        if (status != null) status.setText("Procurando computadores...");
        new Thread(() -> {
            try (DatagramSocket ds = new DatagramSocket()) {
                ds.setBroadcast(true);
                ds.setSoTimeout(300);
                byte[] q = "RM?".getBytes(StandardCharsets.UTF_8);
                List<InetAddress> alvos = new ArrayList<>();
                alvos.add(InetAddress.getByName("255.255.255.255"));
                for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                    if (!ni.isUp() || ni.isLoopback()) continue;
                    for (InterfaceAddress ia : ni.getInterfaceAddresses())
                        if (ia.getBroadcast() != null) alvos.add(ia.getBroadcast());
                }
                for (int r = 0; r < 4; r++) {
                    for (InetAddress a : alvos) {
                        try { ds.send(new DatagramPacket(q, q.length, a, PORTA_BUSCA)); } catch (Exception ignored) { }
                    }
                    long fim = System.currentTimeMillis() + 700;
                    while (System.currentTimeMillis() < fim) {
                        try {
                            DatagramPacket p = new DatagramPacket(new byte[256], 256);
                            ds.receive(p);
                            String s = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                            if (s.startsWith("RM!")) {
                                String[] partes = s.split(",");
                                String ip = p.getAddress().getHostAddress();
                                String nome = partes.length > 1 ? partes[1] : ip;
                                runOnUiThread(() -> adicionarPc(nome, ip));
                            }
                        } catch (SocketTimeoutException e) { break; }
                    }
                }
            } catch (Exception ignored) { }
            runOnUiThread(() -> {
                if (!noPad && status != null && encontrados.isEmpty())
                    status.setText("Nenhum computador encontrado. Confira se o programa está aberto no notebook, ou digite o IP.");
            });
        }).start();
    }

    // ------------------------------------------------------------------ conexão
    void conectar(String ip, String nome) {
        String pin = campoPin.getText().toString().trim();
        if (ip.isEmpty()) { status.setText("Informe o IP ou escolha um computador."); return; }
        if (pin.isEmpty()) { status.setText("Digite o PIN que aparece no notebook."); campoPin.requestFocus(); return; }
        status.setText("Conectando...");
        String apelido = Build.MODEL == null ? "Tablet" : Build.MODEL.replace(",", " ");
        new Thread(() -> {
            Socket s = new Socket();
            boolean conectou = false;
            try {
                s.connect(new InetSocketAddress(ip, PORTA), 4000);
                s.setTcpNoDelay(true);
                s.setSoTimeout(5000);
                OutputStream o = s.getOutputStream();
                o.write(("PIN," + pin + "," + apelido + "\n").getBytes(StandardCharsets.UTF_8));
                o.flush();
                BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                String r = in.readLine();
                if (!"OK".equals(r)) {
                    s.close();
                    runOnUiThread(() -> status.setText("PIN incorreto."));
                    return;
                }
                s.setSoTimeout(0);
                prefs.edit().putString("ip", ip).putString("pin", pin).apply();
                sock = s; conectou = true;
                iniciarEnvio(o);
                runOnUiThread(() -> { nomePc = nome.isEmpty() ? ip : nome; mostrarPad(); });
                String linha;
                while ((linha = in.readLine()) != null) {
                    if (linha.startsWith("r,")) respostas.offer(linha.substring(2));
                }
            } catch (Exception e) {
                if (!conectou) {
                    runOnUiThread(() -> { if (!noPad) status.setText("Não conectou. Confira o IP, a rede e se o programa está aberto no notebook."); });
                }
            }
            if (sock == s) {
                fechar();
                runOnUiThread(() -> { mostrarConexao(); status.setText("Conexão perdida. Toque em Procurar ou Conectar."); });
            }
        }).start();
    }

    void iniciarEnvio(OutputStream o) {
        fila.clear();
        envio = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    StringBuilder sb = new StringBuilder(fila.take()).append('\n');
                    String x;
                    while ((x = fila.poll()) != null) sb.append(x).append('\n');
                    o.write(sb.toString().getBytes(StandardCharsets.UTF_8));
                    o.flush();
                }
            } catch (Exception ignored) { }
        });
        envio.start();
    }

    void enviar(String m) { if (sock != null) fila.offer(m); }

    void enviarTexto(String t) {
        String[] linhas = t.split("\n", -1);
        for (int i = 0; i < linhas.length; i++) {
            if (!linhas[i].isEmpty()) enviar("t," + linhas[i]);
            if (i < linhas.length - 1) enviar("k,enter");
        }
    }

    void fechar() {
        Socket s = sock; sock = null;
        if (envio != null) envio.interrupt();
        if (s != null) try { s.close(); } catch (Exception ignored) { }
    }

    void desconectar() {
        if (segurando) enviar("u,l");
        fechar(); mostrarConexao(); status.setText("Desconectado."); buscar();
    }

    // ------------------------------------------------------------------ tela do controle
    float sensibilidade() { return 0.3f + prefs.getInt("sens", 15) * 0.08f; }

    void mostrarPad() {
        noPad = true; noAssistente = false; segurando = false; anterior = "";
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout raiz = vertical(); raiz.setBackgroundColor(BG); raiz.setPadding(dp(10), dp(10), dp(10), dp(10));

        LinearLayout topo = horizontal();
        topo.addView(texto("● " + nomePc, 15, VERDE), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        topo.addView(texto("Velocidade", 13, SUB));
        SeekBar sb = new SeekBar(this); sb.setMax(40); sb.setProgress(prefs.getInt("sens", 15));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean u) { prefs.edit().putInt("sens", p).apply(); }
            public void onStartTrackingTouch(SeekBar s) { }
            public void onStopTrackingTouch(SeekBar s) { }
        });
        topo.addView(sb, new LinearLayout.LayoutParams(dp(180), ViewGroup.LayoutParams.WRAP_CONTENT));
        Button sair = botao("Sair", VERMELHO, v -> desconectar()); sair.setTextSize(14);
        topo.addView(sair);
        raiz.addView(topo);

        Pad pad = new Pad(this);
        pad.setBackground(fundo(0xFF1C2030));
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        pp.topMargin = dp(8); pp.bottomMargin = dp(8);
        raiz.addView(pad, pp);

        LinearLayout bts = horizontal();
        bts.addView(botao("Esquerdo", CARD, v -> enviar("c,l")), peso(3));
        Button seg = botao("Segurar", CARD, null);
        seg.setOnClickListener(v -> {
            segurando = !segurando;
            enviar(segurando ? "d,l" : "u,l");
            seg.setText(segurando ? "Soltar" : "Segurar");
            seg.setBackground(fundo(segurando ? AZUL : CARD));
        });
        bts.addView(seg, peso(3));
        bts.addView(botao("Direito", CARD, v -> enviar("c,r")), peso(3));
        raiz.addView(bts, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)));

        LinearLayout voz = horizontal();
        voz.addView(botao("🎤 Ditar", 0xFF5B3FD1, v -> ouvir(OUVIR_DITADO, "Fale o texto para digitar")), peso(3));
        voz.addView(botao("🔎 Buscar por voz", 0xFF5B3FD1, v -> ouvir(OUVIR_BUSCA, "O que você quer pesquisar?")), peso(3));
        voz.addView(botao("🤖 Assistente", AZUL, v -> mostrarAssistente()), peso(3));
        raiz.addView(voz, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)));
        raiz.addView(linhaTeclas(new String[][]{
                {"⌨ Teclado", "#kbd"}, {"Enter", "k,enter"}, {"⌫", "k,backspace"}, {"Esc", "k,esc"}, {"Tab", "k,tab"},
                {"←", "k,left"}, {"↑", "k,up"}, {"↓", "k,down"}, {"→", "k,right"}, {"Del", "k,delete"},
                {"⊞ Win", "k,win"}, {"Alt+Tab", "k,alt+tab"}, {"Ctrl+C", "k,ctrl+c"}, {"Ctrl+V", "k,ctrl+v"},
                {"Ctrl+Z", "k,ctrl+z"}, {"Ctrl+A", "k,ctrl+a"}, {"Área de trabalho", "k,win+d"}, {"Fechar", "k,alt+f4"}
        }), mt(8));
        raiz.addView(linhaTeclas(new String[][]{
                {"🔉 Vol−", "k,voldown"}, {"🔊 Vol+", "k,volup"}, {"🔇 Mudo", "k,mute"}, {"⏮", "k,prev"},
                {"⏯", "k,play"}, {"⏭", "k,next"}, {"F5", "k,f5"}, {"Tela cheia (F11)", "k,f11"},
                {"Home", "k,home"}, {"End", "k,end"}, {"PgUp", "k,pgup"}, {"PgDn", "k,pgdn"}
        }), mt(6));

        digitacao = campo("Digite aqui — vai direto para o notebook", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_AUTO_CORRECT);
        digitacao.setImeOptions(EditorInfo.IME_ACTION_SEND | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        digitacao.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { }
            public void afterTextChanged(Editable e) {
                if (ignorarTexto) return;
                String atual = e.toString();
                int p = 0;
                while (p < anterior.length() && p < atual.length() && anterior.charAt(p) == atual.charAt(p)) p++;
                for (int i = 0; i < anterior.length() - p; i++) enviar("k,backspace");
                if (p < atual.length()) enviarTexto(atual.substring(p));
                anterior = atual;
            }
        });
        digitacao.setOnEditorActionListener((v, acao, ev) -> { enviar("k,enter"); limparDigitacao(); return true; });
        digitacao.setOnKeyListener((v, cod, ev) -> {
            if (ev.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (cod == KeyEvent.KEYCODE_DEL && digitacao.getText().length() == 0) { enviar("k,backspace"); return true; }
            if (cod == KeyEvent.KEYCODE_ENTER) { enviar("k,enter"); limparDigitacao(); return true; }
            return false;
        });
        raiz.addView(digitacao, mt(8));
        setContentView(raiz);
    }

    void limparDigitacao() {
        ignorarTexto = true; digitacao.setText(""); ignorarTexto = false; anterior = "";
    }

    View linhaTeclas(String[][] teclas) {
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout l = horizontal();
        for (String[] t : teclas) {
            Button b = botao(t[0], CARD, v -> {
                if (t[1].equals("#kbd")) {
                    digitacao.requestFocus();
                    ((InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE)).showSoftInput(digitacao, InputMethodManager.SHOW_IMPLICIT);
                } else enviar(t[1]);
            });
            b.setTextSize(15);
            b.setMinWidth(dp(64));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(50));
            p.rightMargin = dp(6);
            l.addView(b, p);
        }
        hs.addView(l);
        return hs;
    }

    // ------------------------------------------------------------------ voz
    void ouvir(int codigo, String dica) {
        if (tts != null) tts.stop();
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR");
        i.putExtra(RecognizerIntent.EXTRA_PROMPT, dica);
        try { startActivityForResult(i, codigo); }
        catch (Exception e) { Toast.makeText(this, "Reconhecimento de voz indisponível. Instale/atualize o app Google.", Toast.LENGTH_LONG).show(); }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null) return;
        ArrayList<String> r = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        if (r == null || r.isEmpty()) return;
        String fala = r.get(0);
        if (req == OUVIR_DITADO) enviarTexto(fala);
        else if (req == OUVIR_BUSCA) { enviar("b," + fala); Toast.makeText(this, "Pesquisando: " + fala, Toast.LENGTH_SHORT).show(); }
        else if (req == OUVIR_ASSISTENTE) perguntar(fala);
    }

    void falar(String t) {
        if (tts == null) return;
        tts.speak(t, TextToSpeech.QUEUE_FLUSH, null, "resp");
    }

    // ------------------------------------------------------------------ assistente
    static final String SISTEMA = "Você é o assistente pessoal do usuário, falando português do Brasil. "
            + "Você conversa por voz pelo tablet e controla o notebook Windows dele com as ferramentas. "
            + "Quando ele pedir uma ação, execute na hora com as ferramentas, sem pedir confirmação (o app confirma desligar, reiniciar e suspender). "
            + "Pode encadear várias ferramentas para cumprir um pedido (ex.: abrir o bloco de notas e depois digitar um texto). "
            + "Respostas curtas e naturais para serem faladas: uma ou duas frases, sem markdown, sem listas, sem emojis. "
            + "Para sites conhecidos use o endereço direto (youtube.com, web.whatsapp.com, mail.google.com, instagram.com). "
            + "Se um programa não for encontrado, chame listar_programas e tente de novo com o nome certo.";

    static final String FERRAMENTAS = """
        [
         {"type":"function","function":{"name":"abrir_site","description":"Abre um site ou link no navegador do notebook.",
          "parameters":{"type":"object","properties":{"url":{"type":"string"}},"required":["url"]}}},
         {"type":"function","function":{"name":"pesquisar","description":"Pesquisa algo no Google ou no YouTube, no notebook.",
          "parameters":{"type":"object","properties":{"texto":{"type":"string"},"onde":{"type":"string","enum":["google","youtube"]}},"required":["texto"]}}},
         {"type":"function","function":{"name":"abrir_programa","description":"Abre um programa instalado no notebook pelo nome (ex.: Chrome, Word, Excel, Spotify, WhatsApp, Bloco de Notas, Calculadora).",
          "parameters":{"type":"object","properties":{"nome":{"type":"string"}},"required":["nome"]}}},
         {"type":"function","function":{"name":"listar_programas","description":"Lista os nomes dos programas instalados no notebook.",
          "parameters":{"type":"object","properties":{}}}},
         {"type":"function","function":{"name":"digitar","description":"Digita um texto no notebook, onde o cursor estiver.",
          "parameters":{"type":"object","properties":{"texto":{"type":"string"}},"required":["texto"]}}},
         {"type":"function","function":{"name":"teclas","description":"Aperta uma tecla ou atalho no notebook. Use nomes unidos por +: ctrl, alt, shift, win, enter, esc, tab, backspace, delete, space, left, right, up, down, home, end, pgup, pgdn, f1-f12, letras e números. Mídia: volup, voldown, mute, play, next, prev. Exemplos: ctrl+c, alt+tab, win+d, alt+f4, ctrl+t, ctrl+w.",
          "parameters":{"type":"object","properties":{"atalho":{"type":"string"},"repeticoes":{"type":"integer"}},"required":["atalho"]}}},
         {"type":"function","function":{"name":"energia","description":"Bloqueia, suspende, desliga ou reinicia o notebook, ou cancela um desligamento agendado.",
          "parameters":{"type":"object","properties":{"acao":{"type":"string","enum":["bloquear","suspender","desligar","reiniciar","cancelar_desligamento"]}},"required":["acao"]}}}
        ]""";

    void mostrarAssistente() {
        noAssistente = true;
        LinearLayout raiz = vertical(); raiz.setBackgroundColor(BG); raiz.setPadding(dp(10), dp(10), dp(10), dp(10));
        LinearLayout topo = horizontal();
        TextView tit = texto("🤖 Assistente", 20, Color.WHITE); tit.setTypeface(Typeface.DEFAULT_BOLD);
        topo.addView(tit, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button limpar = botao("Nova conversa", CARD, v -> { historico = new JSONArray(); conversa.clear(); chatLista.removeAllViews(); assistStatus.setText("Conversa reiniciada."); });
        limpar.setTextSize(14); topo.addView(limpar);
        Button cfg = botao("⚙ Chave", CARD, v -> configurarChave()); cfg.setTextSize(14);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT); cp.leftMargin = dp(6);
        topo.addView(cfg, cp);
        Button volta = botao("← Mouse", VERMELHO, v -> { conversaContinua = false; if (tts != null) tts.stop(); mostrarPad(); }); volta.setTextSize(14);
        topo.addView(volta, cp);
        raiz.addView(topo);

        chatScroll = new ScrollView(this);
        chatLista = vertical(); chatLista.setPadding(0, dp(6), 0, dp(6));
        chatScroll.addView(chatLista);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1); sp.topMargin = dp(8);
        raiz.addView(chatScroll, sp);
        for (String[] m : conversa) desenharBalao(m[0], m[1]);

        assistStatus = texto(prefs.getString("chave", "").isEmpty() ? "Toque em ⚙ Chave e cole sua chave (Gemini é grátis)." : "Toque em Falar e peça algo, ex.: \"abre o YouTube e pesquisa louvores\".", 14, AMARELO);
        raiz.addView(assistStatus, mt(4));

        LinearLayout fala = horizontal();
        fala.addView(botao("🎤 Falar", 0xFF5B3FD1, v -> ouvir(OUVIR_ASSISTENTE, "Pode falar...")), peso(3));
        Button cont = botao(conversaContinua ? "Conversa contínua: LIGADA" : "Conversa contínua: desligada", conversaContinua ? VERDE : CARD, null);
        cont.setOnClickListener(v -> {
            conversaContinua = !conversaContinua;
            cont.setText(conversaContinua ? "Conversa contínua: LIGADA" : "Conversa contínua: desligada");
            cont.setBackground(fundo(conversaContinua ? VERDE : CARD));
            if (conversaContinua) ouvir(OUVIR_ASSISTENTE, "Pode falar...");
        });
        cont.setTextSize(14);
        fala.addView(cont, peso(3));
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(60)); fp.topMargin = dp(8);
        raiz.addView(fala, fp);

        LinearLayout escreve = horizontal();
        EditText pergunta = campo("Ou escreva para o assistente", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        pergunta.setImeOptions(EditorInfo.IME_ACTION_SEND);
        Runnable mandar = () -> { String q = pergunta.getText().toString().trim(); if (!q.isEmpty()) { pergunta.setText(""); perguntar(q); } };
        pergunta.setOnEditorActionListener((v, a, e) -> { mandar.run(); return true; });
        escreve.addView(pergunta, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button env = botao("Enviar", AZUL, v -> mandar.run());
        escreve.addView(env, cp);
        raiz.addView(escreve, mt(8));
        setContentView(raiz);
        if (prefs.getString("chave", "").isEmpty()) configurarChave();
    }

    void configurarChave() {
        LinearLayout l = vertical(); l.setPadding(dp(20), dp(10), dp(20), 0);
        EditText chave = new EditText(this); chave.setHint("Chave: Gemini (AIza...) ou OpenAI (sk-...)"); chave.setSingleLine(true);
        chave.setText(prefs.getString("chave", ""));
        EditText modelo = new EditText(this); modelo.setHint("Modelo"); modelo.setSingleLine(true);
        modelo.setText(modeloAtual(prefs.getString("chave", "")));
        l.addView(texto("GRÁTIS: crie a chave no Google AI Studio (aistudio.google.com > Get API key) e cole aqui.\nPago: chave da OpenAI (platform.openai.com). O app detecta qual é pela chave.", 14, Color.GRAY));
        l.addView(chave, mt(8));
        l.addView(texto("Modelo:", 14, Color.GRAY), mt(8));
        l.addView(modelo);
        new AlertDialog.Builder(this).setTitle("Assistente — chave de IA").setView(l)
                .setPositiveButton("Salvar", (d, w) -> {
                    prefs.edit().putString("chave", chave.getText().toString().trim())
                            .putString("modelo", modelo.getText().toString().trim()).apply();
                    usarEsforco = true;
                    if (assistStatus != null) assistStatus.setText("Chave salva. Toque em Falar.");
                })
                .setNegativeButton("Cancelar", null).show();
    }

    void adicionarBalao(String quem, String txt) {
        conversa.add(new String[]{quem, txt});
        if (noAssistente && chatLista != null) desenharBalao(quem, txt);
    }

    void desenharBalao(String quem, String txt) {
        TextView t = texto(txt, 16, Color.WHITE);
        int cor = quem.equals("Você") ? 0xFF2D3F7A : quem.equals("Ação") ? 0xFF1F3A2E : quem.equals("Erro") ? VERMELHO : CARD;
        t.setBackground(fundo(cor)); t.setPadding(dp(14), dp(10), dp(14), dp(10));
        if (quem.equals("Ação")) { t.setTextSize(13); t.setTextColor(0xFF9FE0BD); }
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(6);
        p.gravity = quem.equals("Você") ? Gravity.END : Gravity.START;
        if (quem.equals("Você")) p.leftMargin = dp(80); else p.rightMargin = dp(80);
        chatLista.addView(t, p);
        chatScroll.post(() -> chatScroll.fullScroll(View.FOCUS_DOWN));
    }

    void statusAssistente(String s) { runOnUiThread(() -> { if (assistStatus != null) assistStatus.setText(s); }); }

    void perguntar(String texto) {
        String chave = prefs.getString("chave", "");
        if (chave.isEmpty()) { configurarChave(); return; }
        if (pensando) return;
        pensando = true;
        adicionarBalao("Você", texto);
        statusAssistente("Pensando...");
        new Thread(() -> {
            String resposta = null;
            try {
                if (historico.length() == 0 || historico.length() > 40) {
                    historico = new JSONArray();
                    historico.put(new JSONObject().put("role", "system").put("content", SISTEMA));
                }
                historico.put(new JSONObject().put("role", "user").put("content", texto));
                for (int volta = 0; volta < 8 && resposta == null; volta++) {
                    JSONObject m = chamarOpenAI(chave).getJSONArray("choices").getJSONObject(0).getJSONObject("message");
                    JSONArray calls = m.optJSONArray("tool_calls");
                    JSONObject limpo = new JSONObject().put("role", "assistant");
                    String conteudo = m.isNull("content") ? null : m.optString("content", null);
                    if (conteudo != null) limpo.put("content", conteudo);
                    if (calls != null && calls.length() > 0) limpo.put("tool_calls", calls);
                    historico.put(limpo);
                    if (calls == null || calls.length() == 0) { resposta = conteudo == null ? "Pronto." : conteudo; break; }
                    for (int i = 0; i < calls.length(); i++) {
                        JSONObject c = calls.getJSONObject(i);
                        JSONObject fn = c.getJSONObject("function");
                        String nome = fn.getString("name");
                        JSONObject args;
                        try { args = new JSONObject(fn.optString("arguments", "{}")); } catch (Exception e) { args = new JSONObject(); }
                        statusAssistente("Executando: " + nome.replace('_', ' ') + "...");
                        String res = executarFerramenta(nome, args);
                        String desc = "⚙ " + nome.replace('_', ' ') + " " + resumoArgs(args) + " → " + res;
                        runOnUiThread(() -> adicionarBalao("Ação", desc.length() > 220 ? desc.substring(0, 220) + "…" : desc));
                        historico.put(new JSONObject().put("role", "tool").put("tool_call_id", c.getString("id")).put("content", res));
                    }
                }
                if (resposta == null) resposta = "Pronto.";
                String fr = resposta;
                runOnUiThread(() -> { pensando = false; adicionarBalao("Assistente", fr); statusAssistente(""); falar(fr); });
            } catch (Exception e) {
                historico = new JSONArray();
                String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                runOnUiThread(() -> { pensando = false; adicionarBalao("Erro", msg); statusAssistente(""); falar("Deu um erro. " + (msg.length() < 120 ? msg : "")); });
            }
        }).start();
    }

    String resumoArgs(JSONObject a) {
        StringBuilder sb = new StringBuilder();
        java.util.Iterator<String> it = a.keys();
        while (it.hasNext()) { sb.append(a.optString(it.next())); if (it.hasNext()) sb.append(", "); }
        return sb.length() == 0 ? "" : "(" + sb + ")";
    }

    JSONObject chamarOpenAI(String chave) throws Exception {
        JSONObject corpo = new JSONObject();
        corpo.put("model", modeloAtual(chave));
        corpo.put("messages", historico);
        corpo.put("tools", new JSONArray(FERRAMENTAS));
        if (usarEsforco) corpo.put("reasoning_effort", "low");
        HttpURLConnection c = (HttpURLConnection) new URL(ehGemini(chave) ? "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions" : "https://api.openai.com/v1/chat/completions").openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setConnectTimeout(15000);
        c.setReadTimeout(90000);
        c.setRequestProperty("Authorization", "Bearer " + chave);
        c.setRequestProperty("Content-Type", "application/json");
        try (OutputStream o = c.getOutputStream()) { o.write(corpo.toString().getBytes(StandardCharsets.UTF_8)); }
        int cod = c.getResponseCode();
        InputStream is = cod < 400 ? c.getInputStream() : c.getErrorStream();
        String body = ler(is);
        if (cod == 400 && usarEsforco && body.contains("reasoning")) { usarEsforco = false; return chamarOpenAI(chave); }
        if (cod >= 400) {
            String det = body;
            try { det = new JSONObject(body).getJSONObject("error").optString("message", body); } catch (Exception ignored) { }
            if (cod == 401) throw new Exception("Chave de API inválida. Toque em ⚙ Chave e confira.");
            if (cod == 429) throw new Exception("Limite ou créditos da API esgotados. No Gemini grátis, espere um minuto e tente de novo. (" + det + ")");
            if (cod == 404 || det.contains("model")) throw new Exception("Problema com o modelo \"" + modeloAtual(chave) + "\": " + det + " Troque o modelo em ⚙ Chave.");
            throw new Exception("Erro " + cod + ": " + det);
        }
        return new JSONObject(body);
    }

    static String ler(InputStream is) throws Exception {
        if (is == null) return "";
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192]; int n;
        while ((n = is.read(buf)) > 0) b.write(buf, 0, n);
        is.close();
        return new String(b.toByteArray(), StandardCharsets.UTF_8);
    }

    String esperarResposta() throws InterruptedException {
        String r = respostas.poll(8, TimeUnit.SECONDS);
        return r == null ? "O notebook não respondeu." : r;
    }

    boolean confirmar(String pergunta) throws InterruptedException {
        CountDownLatch l = new CountDownLatch(1);
        boolean[] ok = {false};
        runOnUiThread(() -> new AlertDialog.Builder(this).setMessage(pergunta)
                .setPositiveButton("Sim", (d, w) -> { ok[0] = true; l.countDown(); })
                .setNegativeButton("Não", (d, w) -> l.countDown())
                .setOnCancelListener(d -> l.countDown()).show());
        l.await(60, TimeUnit.SECONDS);
        return ok[0];
    }

    String executarFerramenta(String nome, JSONObject a) throws Exception {
        if (sock == null) return "Erro: o notebook está desconectado.";
        switch (nome) {
            case "abrir_site": {
                String u = a.optString("url").trim();
                if (!u.contains("://")) u = "https://" + u;
                enviar("o," + u); return "Site aberto: " + u;
            }
            case "pesquisar": {
                String t = a.optString("texto");
                if ("youtube".equals(a.optString("onde"))) enviar("o,https://www.youtube.com/results?search_query=" + URLEncoder.encode(t, "UTF-8"));
                else enviar("b," + t);
                return "Pesquisa aberta no navegador.";
            }
            case "abrir_programa": respostas.clear(); enviar("p," + a.optString("nome")); return esperarResposta();
            case "listar_programas": respostas.clear(); enviar("l,"); return esperarResposta();
            case "digitar": enviarTexto(a.optString("texto")); return "Texto digitado.";
            case "teclas": {
                int n = Math.max(1, Math.min(50, a.optInt("repeticoes", 1)));
                for (int i = 0; i < n; i++) enviar("k," + a.optString("atalho").toLowerCase(Locale.ROOT).replace(" ", ""));
                return "Teclas enviadas.";
            }
            case "energia": {
                String ac = a.optString("acao");
                if (!ac.equals("bloquear") && !ac.equals("cancelar_desligamento")
                        && !confirmar("Confirmar: " + ac.replace('_', ' ') + " o notebook?")) return "O usuário não confirmou.";
                respostas.clear(); enviar("e," + ac); return esperarResposta();
            }
        }
        return "Ferramenta desconhecida.";
    }

    // ------------------------------------------------------------------ touchpad
    class Pad extends View {
        float lx, ly, accX, accY, accS, accH, dist;
        int maxDedos;
        long t0, ultimoToque;
        boolean reiniciar, candidatoArrastar, arrastando;

        Pad(Context c) { super(c); }

        float mediaX(MotionEvent e) { float s = 0; for (int i = 0; i < e.getPointerCount(); i++) s += e.getX(i); return s / e.getPointerCount(); }

        float mediaY(MotionEvent e) { float s = 0; for (int i = 0; i < e.getPointerCount(); i++) s += e.getY(i); return s / e.getPointerCount(); }

        @Override public boolean onTouchEvent(MotionEvent e) {
            float d = getResources().getDisplayMetrics().density;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    t0 = e.getEventTime(); maxDedos = 1; dist = 0; accX = accY = accS = accH = 0;
                    lx = e.getX(); ly = e.getY(); reiniciar = false;
                    candidatoArrastar = (t0 - ultimoToque) < 280;
                    return true;
                case MotionEvent.ACTION_POINTER_DOWN:
                case MotionEvent.ACTION_POINTER_UP:
                    maxDedos = Math.max(maxDedos, e.getPointerCount());
                    candidatoArrastar = false; reiniciar = true;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float x = mediaX(e), y = mediaY(e);
                    if (reiniciar) { lx = x; ly = y; reiniciar = false; return true; }
                    float dx = (x - lx) / d, dy = (y - ly) / d;
                    lx = x; ly = y;
                    dist += Math.abs(dx) + Math.abs(dy);
                    boolean moveu = dist > 6;
                    if (e.getPointerCount() == 1 && maxDedos == 1) {
                        if (candidatoArrastar && moveu && !arrastando) {
                            arrastando = true; enviar("d,l");
                            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                        }
                        float s = sensibilidade();
                        accX += dx * s; accY += dy * s;
                        int ix = (int) accX, iy = (int) accY;
                        if (ix != 0 || iy != 0) { accX -= ix; accY -= iy; enviar("m," + ix + "," + iy); }
                    } else if (e.getPointerCount() >= 2) {
                        accS += dy * 4f; accH -= dx * 4f;
                        int is = (int) accS, ih = (int) accH;
                        if (is != 0 && Math.abs(dy) >= Math.abs(dx)) { accS -= is; enviar("s," + is); }
                        if (ih != 0 && Math.abs(dx) > Math.abs(dy)) { accH -= ih; enviar("h," + ih); }
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    long dur = e.getEventTime() - t0;
                    if (arrastando) {
                        enviar("u,l"); arrastando = false; ultimoToque = 0;
                    } else if (dist <= 6 && dur < 260) {
                        if (maxDedos == 1) {
                            enviar("c,l");
                            ultimoToque = candidatoArrastar ? 0 : e.getEventTime();
                        } else if (maxDedos == 2) { enviar("c,r"); ultimoToque = 0; }
                        else { enviar("c,m"); ultimoToque = 0; }
                    }
                    candidatoArrastar = false;
                    return true;
                }
            }
            return true;
        }
    }
}
