package br.marcos.controleremoto;

import android.app.Activity;
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
        mostrarConexao();
        buscar();
    }

    @Override public void onBackPressed() {
        if (noPad) desconectar(); else super.onBackPressed();
    }

    @Override protected void onDestroy() { fechar(); super.onDestroy(); }

    // ------------------------------------------------------------------ tela de conexão
    void mostrarConexao() {
        noPad = false; segurando = false;
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
                while (in.readLine() != null) { /* mantém a conexão */ }
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
        noPad = true; segurando = false; anterior = "";
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
