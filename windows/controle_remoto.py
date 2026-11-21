# -*- coding: utf-8 -*-
"""Controle Remoto - programa do notebook (Windows).
Recebe comandos do app Android por Wi-Fi ou pela rede Bluetooth (ancoragem)
e move o mouse / digita no Windows."""
import base64, glob, json, os, random, socket, struct, subprocess, sys, threading, time, unicodedata, webbrowser
from difflib import get_close_matches
from urllib.parse import quote_plus

PORTA, PORTA_BUSCA = 8765, 8766
WIN = sys.platform.startswith("win")

# ------------------------------------------------------------ entrada do Windows
if WIN:
    import ctypes
    from ctypes import wintypes
    ULONG_PTR = ctypes.c_size_t

    class MOUSEINPUT(ctypes.Structure):
        _fields_ = [("dx", wintypes.LONG), ("dy", wintypes.LONG), ("mouseData", wintypes.DWORD),
                    ("dwFlags", wintypes.DWORD), ("time", wintypes.DWORD), ("dwExtraInfo", ULONG_PTR)]

    class KEYBDINPUT(ctypes.Structure):
        _fields_ = [("wVk", wintypes.WORD), ("wScan", wintypes.WORD), ("dwFlags", wintypes.DWORD),
                    ("time", wintypes.DWORD), ("dwExtraInfo", ULONG_PTR)]

    class HARDWAREINPUT(ctypes.Structure):
        _fields_ = [("uMsg", wintypes.DWORD), ("wParamL", wintypes.WORD), ("wParamH", wintypes.WORD)]

    class _U(ctypes.Union):
        _fields_ = [("mi", MOUSEINPUT), ("ki", KEYBDINPUT), ("hi", HARDWAREINPUT)]

    class INPUT(ctypes.Structure):
        _fields_ = [("type", wintypes.DWORD), ("u", _U)]

    _send = ctypes.windll.user32.SendInput

    def _mouse(flags, dx=0, dy=0, data=0):
        i = INPUT(type=0)
        i.u.mi = MOUSEINPUT(dx, dy, data & 0xFFFFFFFF, flags, 0, 0)
        _send(1, ctypes.byref(i), ctypes.sizeof(INPUT))

    def _key(vk=0, scan=0, flags=0):
        i = INPUT(type=1)
        i.u.ki = KEYBDINPUT(vk, scan, flags, 0, 0)
        _send(1, ctypes.byref(i), ctypes.sizeof(INPUT))
else:  # modo teste fora do Windows
    def _mouse(flags, dx=0, dy=0, data=0): print("mouse", hex(flags), dx, dy, data)
    def _key(vk=0, scan=0, flags=0): print("tecla", hex(vk), scan, hex(flags))

BOTOES = {"l": (0x02, 0x04), "r": (0x08, 0x10), "m": (0x20, 0x40)}
EXT = {0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27, 0x28, 0x2D, 0x2E, 0x5B}
VK = {"enter": 0x0D, "backspace": 0x08, "tab": 0x09, "esc": 0x1B, "space": 0x20,
      "left": 0x25, "up": 0x26, "right": 0x27, "down": 0x28, "delete": 0x2E,
      "home": 0x24, "end": 0x23, "pgup": 0x21, "pgdn": 0x22, "win": 0x5B,
      "ctrl": 0x11, "alt": 0x12, "shift": 0x10, "volup": 0xAF, "voldown": 0xAE,
      "mute": 0xAD, "play": 0xB3, "next": 0xB0, "prev": 0xB1}
VK.update({"f%d" % n: 0x6F + n for n in range(1, 13)})


def vk_de(nome):
    if nome in VK:
        return VK[nome]
    if len(nome) == 1 and nome.isalnum():
        return ord(nome.upper())
    return None


def apertar(vk, solta=False):
    _key(vk=vk, flags=(0x01 if vk in EXT else 0) | (0x02 if solta else 0))


def combo(txt):  # "ctrl+c", "alt+tab", "enter"...
    vks = [vk_de(p) for p in txt.lower().split("+")]
    if None in vks:
        return
    for v in vks:
        apertar(v)
    for v in reversed(vks):
        apertar(v, True)


def digitar(texto):
    b = texto.encode("utf-16-le")
    for u in struct.unpack("<%dH" % (len(b) // 2), b):
        if u in (10, 13):
            combo("enter"); continue
        _key(scan=u, flags=0x04)
        _key(scan=u, flags=0x06)


# ------------------------------------------------------------ programas, sites e energia
_apps = {}
APELIDOS = {"bloco de notas": "notepad", "notepad": "notepad", "calculadora": "calc", "paint": "mspaint",
            "explorador": "explorer", "explorador de arquivos": "explorer", "arquivos": "explorer",
            "meu computador": "explorer", "gerenciador de tarefas": "taskmgr", "cmd": "cmd",
            "prompt de comando": "cmd", "configuracoes": "ms-settings:", "painel de controle": "control",
            "terminal": "wt", "powershell": "powershell"}


def _norm(t):
    t = unicodedata.normalize("NFKD", t.lower().strip())
    return "".join(c for c in t if not unicodedata.combining(c))


def carregar_apps():
    m = {}
    if WIN:
        try:
            out = subprocess.run(["powershell", "-NoProfile", "-Command",
                                  "[Console]::OutputEncoding=[Text.Encoding]::UTF8; Get-StartApps | ConvertTo-Json -Compress"],
                                 capture_output=True, timeout=30, creationflags=0x08000000).stdout.decode("utf-8", "ignore")
            dados = json.loads(out or "[]")
            for a in (dados if isinstance(dados, list) else [dados]):
                m[_norm(a["Name"])] = (a["Name"], "shell:AppsFolder\\" + a["AppID"])
        except Exception as e:
            print("Get-StartApps falhou:", e)
        for pasta in (os.path.join(os.environ.get("ProgramData", ""), r"Microsoft\Windows\Start Menu\Programs"),
                      os.path.join(os.environ.get("APPDATA", ""), r"Microsoft\Windows\Start Menu\Programs")):
            for f in glob.glob(os.path.join(pasta, "**", "*.lnk"), recursive=True):
                n = os.path.splitext(os.path.basename(f))[0]
                m.setdefault(_norm(n), (n, f))
    _apps.clear(); _apps.update(m)


def _abrir(alvo):
    if WIN:
        os.startfile(alvo)
    else:
        print("abrir", alvo)


def abrir_programa(nome):
    n = _norm(nome)
    if not _apps:
        carregar_apps()
    achado = _apps.get(n)
    if not achado:
        contem = [k for k in _apps if n in k] or [k for k in _apps if all(p in k for p in n.split())]
        if contem:
            achado = _apps[min(contem, key=len)]
    if not achado:
        prox = get_close_matches(n, list(_apps), n=1, cutoff=0.6)
        if prox:
            achado = _apps[prox[0]]
    try:
        if achado:
            _abrir(achado[1]); return "Abri o programa: " + achado[0]
        if n in APELIDOS:
            _abrir(APELIDOS[n]); return "Abri: " + nome
    except Exception as e:
        return "Erro ao abrir %s: %s" % (nome, e)
    return "Nao encontrei o programa '%s'. Use listar_programas para ver os nomes." % nome


def energia(acao):
    cmds = {"desligar": ["shutdown", "/s", "/t", "30"], "reiniciar": ["shutdown", "/r", "/t", "30"],
            "cancelar_desligamento": ["shutdown", "/a"], "bloquear": ["rundll32.exe", "user32.dll,LockWorkStation"],
            "suspender": ["rundll32.exe", "powrprof.dll,SetSuspendState", "0,1,0"]}
    if acao not in cmds:
        return "Acao desconhecida."
    if WIN:
        subprocess.Popen(cmds[acao], creationflags=0x08000000)
    else:
        print("energia", acao)
    return {"desligar": "O notebook desliga em 30 segundos.", "reiniciar": "O notebook reinicia em 30 segundos."}.get(acao, "Feito: " + acao)


def executar(msg):
    cmd, _, arg = msg.partition(",")
    if cmd == "o":
        _abrir(arg if "://" in arg or arg.startswith(("ms-", "shell:")) else "https://" + arg); return None
    if cmd == "b":
        webbrowser.open("https://www.google.com/search?q=" + quote_plus(arg)); return None
    if cmd == "p":
        return abrir_programa(arg)
    if cmd == "l":
        if not _apps:
            carregar_apps()
        return "Programas instalados: " + "; ".join(sorted(v[0] for v in _apps.values()))[:6000]
    if cmd == "e":
        return energia(arg)
    executar_entrada(msg)
    return None


def executar_entrada(msg):
    cmd, _, arg = msg.partition(",")
    if cmd == "m":
        dx, dy = arg.split(","); _mouse(0x01, int(dx), int(dy))
    elif cmd == "c":
        d, u = BOTOES.get(arg, BOTOES["l"]); _mouse(d); _mouse(u)
    elif cmd == "d":
        _mouse(BOTOES.get(arg, BOTOES["l"])[0])
    elif cmd == "u":
        _mouse(BOTOES.get(arg, BOTOES["l"])[1])
    elif cmd == "s":
        _mouse(0x0800, data=int(arg))
    elif cmd == "h":
        _mouse(0x1000, data=int(arg))
    elif cmd == "t":
        digitar(arg)
    elif cmd == "k":
        combo(arg)

# ------------------------------------------------------------ configuração (PIN fixo)
PASTA = os.path.join(os.environ.get("APPDATA", os.path.expanduser("~")), "ControleRemoto")
ARQ = os.path.join(PASTA, "config.json")
estado = {"pin": "", "conectados": {}}


def salvar():
    os.makedirs(PASTA, exist_ok=True)
    with open(ARQ, "w") as f:
        json.dump({"pin": estado["pin"]}, f)


def novo_pin():
    estado["pin"] = "%04d" % random.randint(0, 9999); salvar()


try:
    estado["pin"] = json.load(open(ARQ))["pin"]
except Exception:
    novo_pin()

# ------------------------------------------------------------ área de transferência (PC -> tablet)
clientes = {}  # conn -> lock


def ler_clipboard():
    if not WIN:
        return None
    u, k = ctypes.windll.user32, ctypes.windll.kernel32
    u.OpenClipboard.argtypes = [wintypes.HWND]
    u.GetClipboardData.restype = wintypes.HANDLE
    k.GlobalLock.argtypes = [wintypes.HGLOBAL]; k.GlobalLock.restype = ctypes.c_void_p
    k.GlobalUnlock.argtypes = [wintypes.HGLOBAL]
    for _ in range(5):
        if u.OpenClipboard(None):
            break
        time.sleep(0.05)
    else:
        return None
    try:
        h = u.GetClipboardData(13)  # CF_UNICODETEXT
        if not h:
            return None
        ptr = k.GlobalLock(h)
        if not ptr:
            return None
        try:
            return ctypes.wstring_at(ptr)
        finally:
            k.GlobalUnlock(h)
    finally:
        u.CloseClipboard()


def enviar_todos(linha):
    for conn, lock in list(clientes.items()):
        try:
            with lock:
                conn.sendall(linha)
        except OSError:
            pass


def vigiar_clipboard():
    if not WIN:
        return
    seq_fn = ctypes.windll.user32.GetClipboardSequenceNumber
    seq = seq_fn()
    while True:
        time.sleep(0.4)
        n = seq_fn()
        if n == seq:
            continue
        seq = n
        try:
            t = ler_clipboard()
        except Exception:
            t = None
        if t and clientes:
            enviar_todos(("clip," + base64.b64encode(t[:100000].encode("utf-8")).decode() + "\n").encode())


# ------------------------------------------------------------ rede


def atender(conn, addr):
    ip = addr[0]
    try:
        conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        conn.settimeout(10)
        f = conn.makefile("rb")
        nome = ""
        linha = f.readline().decode("utf-8", "ignore").strip()
        partes = linha.split(",", 2)
        if len(partes) < 2 or partes[0] != "PIN" or partes[1] != estado["pin"]:
            time.sleep(1); conn.sendall(b"NO\n"); return
        if len(partes) == 3:
            nome = partes[2]
        conn.sendall(b"OK\n")
        conn.settimeout(None)
        lock = threading.Lock()
        clientes[conn] = lock
        estado["conectados"][ip] = nome or ip
        for raw in f:
            try:
                r = executar(raw.decode("utf-8", "ignore").rstrip("\r\n"))
            except Exception as e:
                r = None; print("erro:", e)
            if r:
                with lock:
                    conn.sendall(("r," + r.replace("\n", " ") + "\n").encode("utf-8"))
    except OSError:
        pass
    finally:
        estado["conectados"].pop(ip, None)
        clientes.pop(conn, None)
        conn.close()


def servidor_tcp(s):
    while True:
        c, a = s.accept()
        threading.Thread(target=atender, args=(c, a), daemon=True).start()


def servidor_busca():
    u = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    u.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    u.bind(("", PORTA_BUSCA))
    while True:
        try:
            data, a = u.recvfrom(64)
            if data.startswith(b"RM?"):
                u.sendto(("RM!,%s,%d" % (socket.gethostname(), PORTA)).encode(), a)
        except OSError:
            pass


def ips():
    s = set()
    try:
        s.update(socket.gethostbyname_ex(socket.gethostname())[2])
    except OSError:
        pass
    try:
        u = socket.socket(socket.AF_INET, socket.SOCK_DGRAM); u.connect(("8.8.8.8", 80))
        s.add(u.getsockname()[0]); u.close()
    except OSError:
        pass
    return sorted(i for i in s if not i.startswith(("127.", "169.254.")))

# ------------------------------------------------------------ janela


def janela():
    import tkinter as tk
    from tkinter import messagebox
    if WIN:
        try:
            ctypes.windll.shcore.SetProcessDpiAwareness(1)
        except Exception:
            pass
    BG, FG, SUB, AZUL, VERDE = "#12141C", "#FFFFFF", "#B0B6C8", "#3B6FF5", "#2EA86B"
    r = tk.Tk()
    r.title("Controle Remoto")
    r.configure(bg=BG)
    r.geometry("440x420")
    r.resizable(False, False)
    tk.Label(r, text="Controle Remoto", font=("Segoe UI", 18, "bold"), fg=FG, bg=BG).pack(pady=(18, 2))
    tk.Label(r, text="Abra o app no tablet e toque no computador.", font=("Segoe UI", 10), fg=SUB, bg=BG).pack()
    tk.Label(r, text="PIN", font=("Segoe UI", 11), fg=SUB, bg=BG).pack(pady=(16, 0))
    lpin = tk.Label(r, text=estado["pin"], font=("Segoe UI", 40, "bold"), fg=AZUL, bg=BG)
    lpin.pack()
    lst = tk.Label(r, text="", font=("Segoe UI", 11, "bold"), fg=VERDE, bg=BG)
    lst.pack(pady=(6, 4))
    lips = tk.Label(r, text="", font=("Consolas", 10), fg=SUB, bg=BG, justify="center")
    lips.pack(pady=4)

    def trocar():
        novo_pin(); lpin.config(text=estado["pin"])

    tk.Button(r, text="Gerar novo PIN", command=trocar, font=("Segoe UI", 10), bg="#262B3A", fg=FG,
              relief="flat", activebackground="#333A4E", activeforeground=FG, padx=12, pady=4).pack(pady=8)
    tk.Label(r, text="Pode minimizar. Fechar a janela desliga o controle.", font=("Segoe UI", 9), fg=SUB, bg=BG).pack(side="bottom", pady=10)

    def atualizar():
        c = list(estado["conectados"].values())
        lst.config(text=("● Conectado: " + ", ".join(c)) if c else "Aguardando o tablet...",
                   fg=VERDE if c else "#FFC857")
        lips.config(text="Endereços deste PC:\n" + "\n".join(ips() or ["(sem rede)"]))
        r.after(1500, atualizar)

    atualizar()
    return r, messagebox


def main():
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    try:
        s.bind(("0.0.0.0", PORTA)); s.listen(5)
    except OSError:
        try:
            import tkinter as tk
            from tkinter import messagebox
            tk.Tk().withdraw()
            messagebox.showinfo("Controle Remoto", "O Controle Remoto já está aberto.")
        except Exception:
            print("Já está aberto.")
        return
    threading.Thread(target=servidor_tcp, args=(s,), daemon=True).start()
    threading.Thread(target=servidor_busca, daemon=True).start()
    threading.Thread(target=carregar_apps, daemon=True).start()
    threading.Thread(target=vigiar_clipboard, daemon=True).start()
    try:
        r, _ = janela()
        r.mainloop()
    except Exception:
        print("PIN:", estado["pin"], "| IPs:", ", ".join(ips()))
        while True:
            time.sleep(3600)


if __name__ == "__main__":
    main()
