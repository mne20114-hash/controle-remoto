package br.marcos.controleremoto;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.speech.RecognizerIntent;
import android.speech.RecognitionListener;
import android.speech.SpeechRecognizer;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioFormat;
import android.media.AudioDeviceInfo;
import android.media.MediaRecorder;
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

    static boolean ehGemini(String chave) { return chave.startsWith("AIza") || chave.startsWith("AQ."); }

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
    String modeloGeminiOk = null;
    List<String> modelosDisponiveis = null;
    final Set<String> semThinking = new HashSet<>();
    SpeechRecognizer reconhecedor;
    boolean ouvindo = false;
    int codigoOuvir = 0, pendenteOuvir = 0;
    TextView avisoVoz;
    volatile boolean micAtivo = false;
    boolean pendenteMic = false;
    Button btMic;

    // pergunta ao Google quais modelos a chave pode usar (flash primeiro, mais novos primeiro)
    List<String> modelosGemini(String chave) {
        if (modelosDisponiveis != null) return modelosDisponiveis;
        List<String> lista = new ArrayList<>();
        try {
            HttpURLConnection c = (HttpURLConnection) new URL("https://generativelanguage.googleapis.com/v1beta/models?pageSize=200").openConnection();
            c.setConnectTimeout(10000); c.setReadTimeout(15000);
            c.setRequestProperty("x-goog-api-key", chave);
            if (c.getResponseCode() >= 400) return lista;
            JSONArray ms = new JSONObject(ler(c.getInputStream())).optJSONArray("models");
            for (int i = 0; ms != null && i < ms.length(); i++) {
                JSONObject m = ms.getJSONObject(i);
                String nome = m.getString("name").replace("models/", "");
                String metodos = String.valueOf(m.optJSONArray("supportedGenerationMethods"));
                if (!metodos.contains("generateContent") || !nome.contains("flash")) continue;
                if (nome.matches(".*(image|tts|audio|live|embed|thinking|exp|computer|robotics).*")) continue;
                lista.add(nome);
            }
            java.util.Collections.sort(lista, (a, b) -> {
                int pa = pontos(a), pb = pontos(b);
                return pa != pb ? Integer.compare(pb, pa) : b.compareTo(a);
            });
        } catch (Exception ignored) { }
        if (!lista.isEmpty()) modelosDisponiveis = lista;
        return lista;
    }

    static int pontos(String n) {
        int p = 0;
        java.util.regex.Matcher v = java.util.regex.Pattern.compile("gemini-(\\d+(?:\\.\\d+)?)").matcher(n);
        if (v.find()) p += (int) (Float.parseFloat(v.group(1)) * 100);
        if (n.contains("preview")) p -= 1000;
        if (n.contains("lite")) p += 50;
        if (n.matches(".*-\\d{3}$") || n.matches(".*\\d{2}-\\d{4}$")) p -= 1;
        if (n.endsWith("-latest")) p -= 2000;
        return p;
    }
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

    @Override protected void onDestroy() { fechar(); if (tts != null) tts.shutdown(); if (reconhecedor != null) reconhecedor.destroy(); super.onDestroy(); }

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
                    else if (linha.startsWith("clip,") && prefs.getBoolean("clip", true)) {
                        try {
                            String t = new String(android.util.Base64.decode(linha.substring(5), android.util.Base64.DEFAULT), StandardCharsets.UTF_8);
                            runOnUiThread(() -> {
                                android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                                cm.setPrimaryClip(android.content.ClipData.newPlainText("Notebook", t));
                                if (Build.VERSION.SDK_INT < 33) Toast.makeText(this, "📋 Copiado do notebook", Toast.LENGTH_SHORT).show();
                            });
                        } catch (Exception ignored) { }
                    }
                }
            } catch (Exception e) {
                if (!conectou) {
                    String m = (e instanceof java.net.ConnectException && String.valueOf(e.getMessage()).contains("ECONNREFUSED"))
                            ? "O notebook recusou: o programa Controle Remoto não está aberto nesse IP."
                            : (e instanceof java.net.SocketTimeoutException || String.valueOf(e.getMessage()).contains("ETIMEDOUT"))
                            ? "Sem resposta do notebook: provável bloqueio do Firewall/antivírus, IP errado ou redes diferentes."
                            : "Não conectou: " + e.getMessage();
                    runOnUiThread(() -> { if (!noPad) status.setText(m); });
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

    // ------------------------------------------------------------------ microfone do tablet no notebook
    void atualizarBotaoMic() {
        if (btMic == null) return;
        btMic.setText(micAtivo ? "🎙 Mic no PC: ON" : "🎙 Mic no PC: OFF");
        btMic.setBackground(fundo(micAtivo ? VERDE : CARD));
    }

    void alternarMic() {
        if (micAtivo) { micAtivo = false; atualizarBotaoMic(); return; }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            pendenteMic = true;
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, 8);
            return;
        }
        iniciarMic();
    }

    void iniciarMic() {
        String ip = prefs.getString("ip", ""), pin = prefs.getString("pin", "");
        micAtivo = true; atualizarBotaoMic();
        new Thread(() -> {
            AudioRecord rec = null;
            Socket s = new Socket();
            String erro = null;
            try {
                s.connect(new InetSocketAddress(ip, 8767), 4000);
                s.setTcpNoDelay(true);
                OutputStream o = s.getOutputStream();
                o.write(("PIN," + pin + "\n").getBytes(StandardCharsets.UTF_8)); o.flush();
                s.setSoTimeout(5000);
                String r = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8)).readLine();
                s.setSoTimeout(0);
                if ("NOCABLE".equals(r)) throw new Exception("Instale o VB-CABLE no notebook (botão na janela do programa) e reinicie o notebook.");
                if (!"OK".equals(r)) throw new Exception("O notebook recusou o microfone. Atualize o programa do notebook.");
                int taxa = 48000;
                int min = AudioRecord.getMinBufferSize(taxa, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                rec = new AudioRecord(MediaRecorder.AudioSource.MIC, taxa, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, 7680));
                AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
                for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_INPUTS))
                    if (d.getType() == AudioDeviceInfo.TYPE_BUILTIN_MIC) { rec.setPreferredDevice(d); break; }
                rec.startRecording();
                runOnUiThread(() -> Toast.makeText(this, "🎙 Microfone do tablet ligado no notebook (\"CABLE Output\")", Toast.LENGTH_LONG).show());
                byte[] buf = new byte[1920];
                while (micAtivo && sock != null) {
                    int n = rec.read(buf, 0, buf.length);
                    if (n > 0) o.write(buf, 0, n);
                }
            } catch (Exception e) {
                if (micAtivo) erro = e.getMessage() == null ? e.toString() : e.getMessage();
            } finally {
                micAtivo = false;
                if (rec != null) { try { rec.stop(); } catch (Exception ignored) { } rec.release(); }
                try { s.close(); } catch (Exception ignored) { }
                String fe = erro;
                runOnUiThread(() -> { atualizarBotaoMic(); if (fe != null) Toast.makeText(this, "Microfone: " + fe, Toast.LENGTH_LONG).show(); });
            }
        }).start();
    }

    void desconectar() {
        micAtivo = false;
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
        Button clip = botao(prefs.getBoolean("clip", true) ? "📋 Copiar do PC: ON" : "📋 Copiar do PC: OFF", prefs.getBoolean("clip", true) ? VERDE : CARD, null);
        clip.setTextSize(14);
        clip.setOnClickListener(v -> {
            boolean on = !prefs.getBoolean("clip", true);
            prefs.edit().putBoolean("clip", on).apply();
            clip.setText(on ? "📋 Copiar do PC: ON" : "📋 Copiar do PC: OFF");
            clip.setBackground(fundo(on ? VERDE : CARD));
        });
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.rightMargin = dp(6);
        topo.addView(clip, clp);
        btMic = botao("", CARD, v -> alternarMic()); btMic.setTextSize(14);
        atualizarBotaoMic();
        topo.addView(btMic, clp);
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

        avisoVoz = texto("", 14, AMARELO); avisoVoz.setVisibility(View.GONE);
        raiz.addView(avisoVoz, mt(4));
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
    void avisoOuvindo(String t) {
        if (noAssistente && assistStatus != null) assistStatus.setText(t);
        else if (avisoVoz != null) { avisoVoz.setText(t); avisoVoz.setVisibility(t.isEmpty() ? View.GONE : View.VISIBLE); }
    }

    void usarMicDoTablet() {
        try {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice();
            if (am.isBluetoothScoOn()) { am.stopBluetoothSco(); am.setBluetoothScoOn(false); }
            am.setMode(AudioManager.MODE_NORMAL);
        } catch (Exception ignored) { }
    }

    void ouvir(int codigo, String dica) {
        if (tts != null) tts.stop();
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            pendenteOuvir = codigo;
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, 7);
            return;
        }
        if (micAtivo) { Toast.makeText(this, "Desligue o \"Mic no PC\" para usar a voz do app.", Toast.LENGTH_LONG).show(); return; }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { ouvirIntent(codigo, dica); return; }
        if (ouvindo && reconhecedor != null) { reconhecedor.stopListening(); return; }
        usarMicDoTablet();
        if (reconhecedor == null) {
            reconhecedor = SpeechRecognizer.createSpeechRecognizer(this);
            reconhecedor.setRecognitionListener(new RecognitionListener() {
                public void onReadyForSpeech(Bundle b) { avisoOuvindo("🎙 Ouvindo... pode falar (toque de novo para parar)"); }
                public void onBeginningOfSpeech() { }
                public void onRmsChanged(float v) { }
                public void onBufferReceived(byte[] b) { }
                public void onEndOfSpeech() { avisoOuvindo("Entendendo..."); }
                public void onEvent(int t, Bundle b) { }
                public void onPartialResults(Bundle b) {
                    ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (r != null && !r.isEmpty() && !r.get(0).isEmpty()) avisoOuvindo("🎙 " + r.get(0));
                }
                public void onError(int e) {
                    ouvindo = false;
                    avisoOuvindo(e == SpeechRecognizer.ERROR_NO_MATCH || e == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ? "Não ouvi nada. Toque e fale de novo."
                            : e == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ? "Permita o microfone para o app." : "Falha no microfone (erro " + e + "). Tente de novo.");
                }
                public void onResults(Bundle b) {
                    ouvindo = false;
                    ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    avisoOuvindo("");
                    if (r != null && !r.isEmpty()) tratarFala(codigoOuvir, r.get(0));
                }
            });
        }
        codigoOuvir = codigo;
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR");
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1000);
        ouvindo = true;
        avisoOuvindo("🎙 Preparando o microfone...");
        reconhecedor.startListening(i);
    }

    @Override public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req == 8 && res.length > 0 && res[0] == android.content.pm.PackageManager.PERMISSION_GRANTED && pendenteMic) {
            pendenteMic = false; iniciarMic();
        }
        if (req == 7 && res.length > 0 && res[0] == android.content.pm.PackageManager.PERMISSION_GRANTED && pendenteOuvir > 0) {
            int c = pendenteOuvir; pendenteOuvir = 0; ouvir(c, "");
        }
    }

    void tratarFala(int req, String fala) {
        if (req == OUVIR_DITADO) enviarTexto(fala);
        else if (req == OUVIR_BUSCA) { enviar("b," + fala); Toast.makeText(this, "Pesquisando: " + fala, Toast.LENGTH_SHORT).show(); }
        else if (req == OUVIR_ASSISTENTE) perguntar(fala);
    }

    void ouvirIntent(int codigo, String dica) {
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
        tratarFala(req, r.get(0));
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
        EditText chave = new EditText(this); chave.setHint("Chave: Gemini (AQ... / AIza...) ou OpenAI (sk-...)"); chave.setSingleLine(true);
        chave.setText(prefs.getString("chave", ""));
        EditText modelo = new EditText(this); modelo.setHint("Modelo"); modelo.setSingleLine(true);
        modelo.setText(modeloAtual(prefs.getString("chave", "")));
        l.addView(texto("GRÁTIS: crie a chave no Google AI Studio (aistudio.google.com > Get API key) e cole aqui (AIza... ou AQ....).\nPago: chave da OpenAI (platform.openai.com). O app detecta qual é pela chave.", 14, Color.GRAY));
        l.addView(chave, mt(8));
        l.addView(texto("Modelo:", 14, Color.GRAY), mt(8));
        l.addView(modelo);
        new AlertDialog.Builder(this).setTitle("Assistente — chave de IA").setView(l)
                .setPositiveButton("Salvar", (d, w) -> {
                    prefs.edit().putString("chave", chave.getText().toString().trim())
                            .putString("modelo", modelo.getText().toString().trim()).apply();
                    usarEsforco = true; historico = new JSONArray();
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
        if (pensando) return;
        pensando = true;
        adicionarBalao("Você", texto);
        statusAssistente("Pensando...");
        new Thread(() -> {
            String resposta = null;
            try {
                String rapido = null;
                try { rapido = comandoLocal(texto); } catch (Exception ignored) { }
                if (rapido != null) {
                    String fr = rapido;
                    runOnUiThread(() -> { pensando = false; adicionarBalao("Assistente", "⚡ " + fr); statusAssistente(""); falar(fr); });
                    return;
                }
                if (chave.isEmpty()) {
                    runOnUiThread(() -> { pensando = false; adicionarBalao("Assistente", "Esse pedido precisa da IA. Cole a chave em ⚙ Chave."); configurarChave(); });
                    return;
                }
                if (historico.length() == 0 || historico.length() > 40) {
                    historico = new JSONArray();
                    historico.put(new JSONObject().put("role", "system").put("content", SISTEMA));
                }
                historico.put(new JSONObject().put("role", "user").put("content", texto));
                for (int volta = 0; volta < 8 && resposta == null; volta++) {
                    JSONObject m = ehGemini(chave) ? chamarGemini(chave)
                            : chamarOpenAI(chave).getJSONArray("choices").getJSONObject(0).getJSONObject("message");
                    JSONArray calls = m.optJSONArray("tool_calls");
                    JSONObject limpo = new JSONObject().put("role", "assistant");
                    String conteudo = m.isNull("content") ? null : m.optString("content", null);
                    if (conteudo != null) limpo.put("content", conteudo);
                    if (calls != null && calls.length() > 0) limpo.put("tool_calls", calls);
                    if (m.has("_gparts")) limpo.put("_gparts", m.get("_gparts"));
                    historico.put(limpo);
                    if (calls == null || calls.length() == 0) { resposta = conteudo == null ? "Pronto." : conteudo; break; }
                    boolean precisaVoltar = false;
                    List<String> feitos = new ArrayList<>();
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
                        if (nome.equals("listar_programas") || res.startsWith("Nao encontrei") || res.startsWith("Erro") || res.startsWith("O notebook")) precisaVoltar = true;
                        else feitos.add(nome.equals("abrir_programa") || nome.equals("energia") ? res : confirmacao(nome));
                    }
                    if (!precisaVoltar) {
                        resposta = (conteudo != null && !conteudo.trim().isEmpty()) ? conteudo.trim() : String.join(" ", new java.util.LinkedHashSet<>(feitos));
                        historico.put(new JSONObject().put("role", "assistant").put("content", resposta));
                        break;
                    }
                }
                if (resposta == null) resposta = "Pronto.";
                String fr = resposta;
                runOnUiThread(() -> { pensando = false; adicionarBalao("Assistente", fr); statusAssistente(""); falar(fr); });
            } catch (Exception e) {
                historico = new JSONArray();
                String msg = (e instanceof java.net.SocketTimeoutException || "timeout".equals(e.getMessage()))
                        ? "A IA demorou demais para responder. Tente de novo."
                        : (e.getMessage() == null ? e.toString() : e.getMessage());
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

    // Gemini nativo (aceita chaves AIza... e AQ....)
    JSONObject chamarGemini(String chave) throws Exception {
        JSONObject corpo = new JSONObject();
        JSONArray contents = new JSONArray();
        java.util.Map<String, String> nomes = new java.util.HashMap<>();
        JSONObject ultimoUser = null; boolean ultimoEhTool = false;
        for (int i = 0; i < historico.length(); i++) {
            JSONObject h = historico.getJSONObject(i);
            String role = h.getString("role");
            if (role.equals("system")) {
                corpo.put("systemInstruction", new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", h.getString("content")))));
            } else if (role.equals("user")) {
                contents.put(new JSONObject().put("role", "user").put("parts", new JSONArray().put(new JSONObject().put("text", h.getString("content")))));
                ultimoEhTool = false;
            } else if (role.equals("assistant")) {
                JSONArray parts;
                if (h.has("_gparts")) parts = h.getJSONArray("_gparts");
                else { parts = new JSONArray(); if (h.has("content")) parts.put(new JSONObject().put("text", h.getString("content"))); }
                JSONArray tc = h.optJSONArray("tool_calls");
                if (tc != null) for (int k = 0; k < tc.length(); k++)
                    nomes.put(tc.getJSONObject(k).getString("id"), tc.getJSONObject(k).getJSONObject("function").getString("name"));
                if (parts.length() > 0) contents.put(new JSONObject().put("role", "model").put("parts", parts));
                ultimoEhTool = false;
            } else if (role.equals("tool")) {
                String id = h.getString("tool_call_id");
                JSONObject fr = new JSONObject().put("functionResponse", new JSONObject()
                        .put("name", nomes.containsKey(id) ? nomes.get(id) : "ferramenta")
                        .put("response", new JSONObject().put("resultado", h.getString("content"))));
                if (!ultimoEhTool) { ultimoUser = new JSONObject().put("role", "user").put("parts", new JSONArray()); contents.put(ultimoUser); }
                ultimoUser.getJSONArray("parts").put(fr);
                ultimoEhTool = true;
            }
        }
        corpo.put("contents", contents);
        JSONArray decl = new JSONArray();
        JSONArray ferr = new JSONArray(FERRAMENTAS);
        for (int i = 0; i < ferr.length(); i++) {
            JSONObject f = ferr.getJSONObject(i).getJSONObject("function");
            JSONObject d = new JSONObject().put("name", f.getString("name")).put("description", f.getString("description"));
            JSONObject params = f.getJSONObject("parameters");
            if (params.getJSONObject("properties").length() > 0) d.put("parameters", params);
            decl.put(d);
        }
        corpo.put("tools", new JSONArray().put(new JSONObject().put("functionDeclarations", decl)));
        java.util.LinkedHashSet<String> modelos = new java.util.LinkedHashSet<>();
        if (modeloGeminiOk != null) modelos.add(modeloGeminiOk);
        List<String> disp = modelosGemini(chave);
        String escolhido = modeloAtual(chave);
        if (disp.isEmpty() || disp.contains(escolhido)) modelos.add(escolhido);
        for (String m : disp) { if (modelos.size() >= 5) break; modelos.add(m); }
        if (disp.isEmpty()) { modelos.add("gemini-3.5-flash"); modelos.add("gemini-3.5-flash-lite"); modelos.add("gemini-flash-latest"); }
        String body = null, aviso = "";
        StringBuilder erros = new StringBuilder();
        externo:
        for (String modelo : modelos) {
            boolean pensarPouco = !semThinking.contains(modelo);
            for (int tent = 0; tent < 2; tent++) {
                JSONObject envio = new JSONObject(corpo.toString());
                if (pensarPouco) envio.put("generationConfig", new JSONObject().put("thinkingConfig", new JSONObject().put("thinkingLevel", "minimal")));
                int cod; String b;
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL("https://generativelanguage.googleapis.com/v1beta/models/" + modelo + ":generateContent").openConnection();
                    c.setRequestMethod("POST"); c.setDoOutput(true); c.setConnectTimeout(10000); c.setReadTimeout(25000);
                    c.setRequestProperty("x-goog-api-key", chave);
                    c.setRequestProperty("Content-Type", "application/json");
                    try (OutputStream o = c.getOutputStream()) { o.write(envio.toString().getBytes(StandardCharsets.UTF_8)); }
                    cod = c.getResponseCode();
                    b = ler(cod < 400 ? c.getInputStream() : c.getErrorStream());
                } catch (java.net.SocketTimeoutException e) {
                    erros.append("\n• ").append(modelo).append(": demorou demais");
                    if (modelo.equals(modeloGeminiOk)) modeloGeminiOk = null;
                    statusAssistente("Demorou, tentando outro modelo...");
                    continue externo;
                }
                if (cod == 400 && pensarPouco && b.toLowerCase(Locale.ROOT).contains("think")) {
                    semThinking.add(modelo); pensarPouco = false; tent--; continue;
                }
                if (cod < 400) { body = b; modeloGeminiOk = modelo; break externo; }
                String det = b;
                try { det = new JSONObject(b).getJSONObject("error").optString("message", b); } catch (Exception ignored) { }
                if (cod == 401 || cod == 403) throw new Exception("Chave do Gemini recusada (" + det + "). Gere outra em aistudio.google.com e cole em ⚙ Chave.");
                aviso = "Erro " + cod + ": " + det;
                if (tent == 0) erros.append("\n• ").append(modelo).append(": ").append(cod == 503 ? "ocupado" : cod == 429 ? "limite grátis atingido" : cod == 404 ? "indisponível" : "erro " + cod);
                if (cod == 404) modelosDisponiveis = null;
                if (cod == 500 || cod == 503) { statusAssistente("Gemini ocupado, tentando de novo..."); Thread.sleep(1500); continue; }
                if (cod == 429 || cod == 404) { statusAssistente("Tentando outro modelo do Gemini..."); continue externo; }
                if (cod == 400 && modelo.equals(modeloGeminiOk)) { modeloGeminiOk = null; }
                throw new Exception(aviso);
            }
            statusAssistente("Gemini ocupado, tentando outro modelo...");
        }
        if (body == null) throw new Exception("Nenhum modelo do Gemini respondeu agora. Tente de novo em 1 minuto." + erros);
        JSONObject r = new JSONObject(body);
        JSONArray cands = r.optJSONArray("candidates");
        if (cands == null || cands.length() == 0) throw new Exception("O Gemini não respondeu. Tente de novo.");
        JSONObject content = cands.getJSONObject(0).optJSONObject("content");
        JSONArray parts = content == null ? new JSONArray() : content.optJSONArray("parts");
        if (parts == null) parts = new JSONArray();
        StringBuilder txt = new StringBuilder();
        JSONArray calls = new JSONArray();
        for (int i = 0; i < parts.length(); i++) {
            JSONObject pt = parts.getJSONObject(i);
            if (pt.optBoolean("thought", false)) continue;
            if (pt.has("text")) txt.append(pt.getString("text"));
            if (pt.has("functionCall")) {
                JSONObject fc = pt.getJSONObject("functionCall");
                calls.put(new JSONObject().put("id", fc.optString("id", "chamada_" + System.nanoTime() + "_" + i)).put("type", "function")
                        .put("function", new JSONObject().put("name", fc.getString("name"))
                                .put("arguments", fc.optJSONObject("args") == null ? "{}" : fc.getJSONObject("args").toString())));
            }
        }
        JSONObject m = new JSONObject().put("role", "assistant").put("_gparts", parts);
        if (txt.length() > 0) m.put("content", txt.toString().trim());
        if (calls.length() > 0) m.put("tool_calls", calls);
        return m;
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

    String confirmacao(String ferramenta) {
        switch (ferramenta) {
            case "abrir_site": return "Pronto, abri o site.";
            case "pesquisar": return "Pronto, pesquisei.";
            case "digitar": return "Digitei.";
            default: return "Feito.";
        }
    }

    // ------------------------------------------------------------------ comandos rápidos (sem IA)
    static String norm(String t) {
        String n = java.text.Normalizer.normalize(t.toLowerCase(Locale.ROOT), java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.replaceAll("[!?,;:]|\\.(?=\\s|$)", " ").replaceAll("\\s+", " ").trim();
    }

    static final String[][] SITES = {
            {"youtube", "https://www.youtube.com", "o YouTube"}, {"google", "https://www.google.com", "o Google"},
            {"gmail", "https://mail.google.com", "o Gmail"}, {"e-mail", "https://mail.google.com", "o Gmail"}, {"email", "https://mail.google.com", "o Gmail"},
            {"whatsapp", "https://web.whatsapp.com", "o WhatsApp"}, {"instagram", "https://www.instagram.com", "o Instagram"},
            {"facebook", "https://www.facebook.com", "o Facebook"}, {"netflix", "https://www.netflix.com", "a Netflix"},
            {"tiktok", "https://www.tiktok.com", "o TikTok"}, {"chatgpt", "https://chatgpt.com", "o ChatGPT"},
            {"chat gpt", "https://chatgpt.com", "o ChatGPT"}, {"google drive", "https://drive.google.com", "o Drive"},
            {"drive", "https://drive.google.com", "o Drive"}, {"mercado livre", "https://www.mercadolivre.com.br", "o Mercado Livre"},
            {"shopee", "https://shopee.com.br", "a Shopee"}, {"github", "https://github.com", "o GitHub"},
            {"canva", "https://www.canva.com", "o Canva"}, {"maps", "https://maps.google.com", "o Maps"},
            {"google maps", "https://maps.google.com", "o Maps"}, {"tradutor", "https://translate.google.com", "o Tradutor"}};

    static final String[][] TECLAS = {
            {"nova aba", "ctrl+t", "Abri uma nova aba."}, {"nova guia", "ctrl+t", "Abri uma nova aba."},
            {"fecha a aba", "ctrl+w", "Fechei a aba."}, {"fechar a aba", "ctrl+w", "Fechei a aba."}, {"fecha aba", "ctrl+w", "Fechei a aba."},
            {"fecha essa aba", "ctrl+w", "Fechei a aba."}, {"reabre a aba", "ctrl+shift+t", "Reabri a aba."},
            {"proxima aba", "ctrl+tab", "Pronto."}, {"aba anterior", "ctrl+shift+tab", "Pronto."},
            {"volta a pagina", "alt+left", "Voltei."}, {"voltar a pagina", "alt+left", "Voltei."}, {"pagina anterior", "alt+left", "Voltei."},
            {"atualiza", "f5", "Atualizei."}, {"atualizar", "f5", "Atualizei."}, {"recarrega", "f5", "Atualizei."},
            {"tela cheia", "f11", "Pronto."}, {"area de trabalho", "win+d", "Pronto."}, {"minimiza tudo", "win+d", "Pronto."},
            {"fecha o programa", "alt+f4", "Fechei."}, {"fecha a janela", "alt+f4", "Fechei."}, {"fechar a janela", "alt+f4", "Fechei."},
            {"fecha isso", "alt+f4", "Fechei."}, {"troca de janela", "alt+tab", "Pronto."}, {"muda de janela", "alt+tab", "Pronto."},
            {"pausa", "play", "Pronto."}, {"pause", "play", "Pronto."}, {"pausar", "play", "Pronto."}, {"despausa", "play", "Pronto."},
            {"continua", "play", "Pronto."}, {"da play", "play", "Pronto."}, {"play", "play", "Pronto."},
            {"proxima musica", "next", "Próxima."}, {"proximo video", "next", "Próximo."}, {"musica anterior", "prev", "Anterior."},
            {"muta", "mute", "Pronto."}, {"mutar", "mute", "Pronto."}, {"silencia", "mute", "Pronto."}, {"tira o som", "mute", "Pronto."},
            {"volta o som", "mute", "Pronto."}, {"copia", "ctrl+c", "Copiei."}, {"copiar", "ctrl+c", "Copiei."},
            {"cola", "ctrl+v", "Colei."}, {"colar", "ctrl+v", "Colei."}, {"desfaz", "ctrl+z", "Desfeito."}, {"desfazer", "ctrl+z", "Desfeito."},
            {"seleciona tudo", "ctrl+a", "Selecionei."}, {"salva", "ctrl+s", "Salvei."}, {"salvar", "ctrl+s", "Salvei."},
            {"aperta enter", "enter", "Pronto."}, {"da enter", "enter", "Pronto."}, {"enter", "enter", "Pronto."}};

    static final String[] PREFIXOS = {"eu quero que voce ", "quero que voce ", "voce pode ", "voce poderia ", "pode ", "por favor ",
            "assistente ", "ei ", "oi ", "me ", "agora ", "eu quero ", "quero ", "preciso que voce ", "faz o favor de ", "faca "};

    String limpar(String n) {
        boolean mudou = true;
        while (mudou) {
            mudou = false;
            for (String p : PREFIXOS) if (n.startsWith(p)) { n = n.substring(p.length()); mudou = true; }
        }
        return n.replaceAll("\\b(agora|por favor|pra mim|para mim|ai)\\b", " ").replaceAll("\\s+", " ").trim();
    }

    String[] acaoDe(String parte, String[] anterior) {
        String n = limpar(parte);
        if (n.isEmpty()) return null;
        String sp = " " + n + " ";
        for (String[] t : TECLAS) if (sp.contains(" " + t[0] + " ")) return new String[]{"k", t[1], t[2], "1"};
        if (n.contains("volume") || n.contains("som")) {
            int vezes = n.contains("muito") || n.contains("bastante") ? 10 : 5;
            if (n.matches(".*\\b(aumenta|aumentar|sobe|subir|mais alto)\\b.*")) return new String[]{"k", "volup", "Aumentei o volume.", "" + vezes};
            if (n.matches(".*\\b(diminui|diminuir|abaixa|abaixar|baixa|baixar|mais baixo)\\b.*")) return new String[]{"k", "voldown", "Abaixei o volume.", "" + vezes};
        }
        if (n.matches("^(bloqueia|bloquear|trava|travar)( o)? (notebook|computador|pc|tela)$")) return new String[]{"e", "bloquear", "Bloqueei o notebook.", "1"};
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(pesquis\\w*|procur\\w*|busc\\w*)( por| sobre| o| a)? (.+?)( no youtube| na youtube| no google)?$").matcher(n);
        if (m.find()) {
            String q = m.group(3).trim(), onde = m.group(4) == null ? "" : m.group(4);
            boolean yt = onde.contains("youtube") || (anterior != null && anterior[1].contains("youtube.com"));
            if (q.isEmpty()) return null;
            return new String[]{yt ? "y" : "s", q, yt ? "Pesquisando \"" + q + "\" no YouTube." : "Pesquisando \"" + q + "\".", "1"};
        }
        m = java.util.regex.Pattern.compile("^(abr\\w*|entr\\w* (no|na|em)|vai (no|na|para|pro|pra)|ir (no|na|para|pro|pra))( o| a| os| as| um| uma)? (.+)$").matcher(n);
        if (m.find()) {
            String alvo = m.group(m.groupCount()).trim();
            alvo = alvo.replaceFirst("^(o |a )?(site|pagina|endereco) (do |da |de )?", "");
            for (String[] s : SITES) if (alvo.equals(s[0]) || alvo.equals("site do " + s[0]) || alvo.equals("site da " + s[0]) || alvo.equals(s[0] + " web"))
                return new String[]{"o", s[1], "Abrindo " + s[2] + ".", "1"};
            if (alvo.matches("^[a-z0-9-]+(\\.[a-z0-9-]+)+(/.*)?$")) return new String[]{"o", "https://" + alvo, "Abrindo " + alvo + ".", "1"};
            if (alvo.split(" ").length <= 4) return new String[]{"p", alvo, "", "1"};
        }
        return null;
    }

    // devolve a frase falada se resolveu sem IA; null para mandar à IA
    String comandoLocal(String texto) throws Exception {
        if (sock == null) return null;
        String n = norm(texto);
        String[] partes = n.split(" e (depois |em seguida |entao )?");
        List<String[]> acoes = new ArrayList<>();
        String[] anterior = null;
        for (String p : partes) {
            String[] a = acaoDe(p, anterior);
            if (a == null) return null;
            if (anterior != null && anterior[0].equals("o") && anterior[1].contains("youtube.com") && a[0].equals("y")) acoes.remove(acoes.size() - 1);
            acoes.add(a); anterior = a;
        }
        if (acoes.isEmpty()) return null;
        StringBuilder fala = new StringBuilder();
        for (int i = 0; i < acoes.size(); i++) {
            String[] a = acoes.get(i);
            switch (a[0]) {
                case "k": for (int r = 0; r < Integer.parseInt(a[3]); r++) enviar("k," + a[1]); break;
                case "o": enviar("o," + a[1]); break;
                case "s": enviar("b," + a[1]); break;
                case "y": enviar("o,https://www.youtube.com/results?search_query=" + URLEncoder.encode(a[1], "UTF-8")); break;
                case "e": enviar("e," + a[1]); break;
                case "p": {
                    respostas.clear(); enviar("p," + a[1]);
                    String r = esperarResposta();
                    if (!r.startsWith("Abri")) {
                        if (i == 0 && !prefs.getString("chave", "").isEmpty()) return null;
                        a[2] = "Não encontrei o programa " + a[1] + ".";
                    } else a[2] = r.replace("Abri o programa:", "Abrindo").replace("Abri:", "Abrindo") + ".";
                    break;
                }
            }
            fala.append(a[2]).append(' ');
            if (i < acoes.size() - 1) Thread.sleep(a[0].equals("o") || a[0].equals("p") ? 1500 : 300);
        }
        return fala.toString().trim();
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
