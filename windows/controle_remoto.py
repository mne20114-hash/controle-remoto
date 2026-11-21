# -*- coding: utf-8 -*-
"""Controle Remoto - programa do notebook (Windows).
Recebe comandos do app Android por Wi-Fi ou pela rede Bluetooth (ancoragem)
e move o mouse / digita no Windows."""
import json, os, random, socket, struct, sys, threading, time

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


def executar(msg):
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
        estado["conectados"][ip] = nome or ip
        for raw in f:
            try:
                executar(raw.decode("utf-8", "ignore").rstrip("\r\n"))
            except Exception as e:
                print("erro:", e)
    except OSError:
        pass
    finally:
        estado["conectados"].pop(ip, None)
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
    try:
        r, _ = janela()
        r.mainloop()
    except Exception:
        print("PIN:", estado["pin"], "| IPs:", ", ".join(ips()))
        while True:
            time.sleep(3600)


if __name__ == "__main__":
    main()
