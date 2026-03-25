package org.vinni.servidor.gui;

import javax.swing.*;
import java.io.*;
import java.net.*;
import java.awt.*;
import java.awt.event.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Author: Vinni 2024 | Nathalie Pinzón 2026
 */
public class PrincipalSrv extends JFrame {

    // ── Configuración ─────────────────────────────────────────
    private static final int    PORT                = 12345;
    private static final int    PORT_ARCHIVOS       = 12346; // canal exclusivo para archivos
    private static final int    DELAY_REINICIO      = 5;
    private static final int    INTERVALO_HEARTBEAT = 30;
    private static final int    TIMEOUT_HEARTBEAT   = 10;

    // ── Validaciones ──────────────────────────────────────────
    private static final int      MAX_CLIENTES        = 10;
    private static final String   REGEX_NOMBRE        = "^[a-zA-Z0-9_]{2,20}$";
    private static final String[] PALABRAS_RESERVADAS = {"SERVIDOR", "TODOS", "*"};
    private static final String[] EXT_BLOQUEADAS      = {".exe", ".bat", ".sh", ".cmd", ".msi"};
    private static final long     MAX_TAMANIO_ARCHIVO = 10 * 1024 * 1024;

    // ── Rutas de persistencia ─────────────────────────────────
    private static final String BASE_DIR    = System.getProperty("user.home") + File.separator + "ServidorTCP";
    private static final String DIR_LOGS    = BASE_DIR + File.separator + "logs";
    private static final String DIR_ESTADO  = BASE_DIR + File.separator + "estado";
    private static final String DIR_ARCHIVOS= BASE_DIR + File.separator + "archivos";
    private static final String ARCHIVO_COLA= DIR_ESTADO + File.separator + "cola_mensajes.txt";
    private static final String ARCHIVO_CLI = DIR_ESTADO + File.separator + "clientes.txt";

    /** [FIX] Ruta del flag que distingue apagado manual de caída inesperada */
    static final String FLAG_APAGADO_MANUAL = DIR_ESTADO + File.separator + "apagado_manual.flag";

    // ── Estado del servidor ───────────────────────────────────
    private ServerSocket        serverSocket;
    private ServerSocket        serverSocketArchivos; // canal de archivos
    private final AtomicBoolean activo        = new AtomicBoolean(false);
    private final AtomicBoolean apagadoManual = new AtomicBoolean(false);

    private final Map<String, ClientHandler> clientes     = new ConcurrentHashMap<>();
    private final Map<String, Queue<String>> colaMensajes = new ConcurrentHashMap<>();

    private ScheduledExecutorService heartbeatScheduler;

    // ── GUI ───────────────────────────────────────────────────
    private JButton   btnIniciar;
    private JButton   btnApagar;
    private JTextArea logArea;
    private JLabel    lblEstado;
    private JLabel    lblClientes;
    private JLabel    lblCola;

    public PrincipalSrv() {
        initComponents();
        crearDirectorios();
    }

    // ── INIT GUI ─────────────────────────────────────────────
    private void initComponents() {
        setTitle("Servidor TCP");
        setSize(680, 580);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                // ── ESCENARIO 3 ────────────────────────────────
                // Cerrar con X simula caída INESPERADA:
                // NO se escribe el flag → Watchdog SÍ reinicia.
                logUI("[VENTANA] Cerrando servidor (caída inesperada para el Watchdog)...");
                activo.set(false);
                guardarEstado();           // guardar cola y clientes
                cerrarServerSocket();
                System.exit(0);
            }
        });

        setLocationRelativeTo(null);
        setLayout(new BorderLayout(10, 10));

        // NORTE
        JPanel panelNorte = new JPanel(new BorderLayout(10, 5));
        panelNorte.setBorder(BorderFactory.createEmptyBorder(15, 20, 5, 20));

        JLabel titulo = new JLabel("SERVIDOR TCP");
        titulo.setFont(new Font("Dialog", Font.BOLD, 15));
        titulo.setForeground(new Color(160, 0, 0));

        lblEstado   = new JLabel("⬤ Detenido");
        lblEstado.setFont(new Font("Dialog", Font.BOLD, 12));
        lblEstado.setForeground(Color.GRAY);

        lblClientes = new JLabel("Clientes: 0");
        lblClientes.setFont(new Font("Dialog", Font.PLAIN, 12));

        lblCola     = new JLabel("Cola: 0 mensajes");
        lblCola.setFont(new Font("Dialog", Font.PLAIN, 12));
        lblCola.setForeground(new Color(150, 80, 0));

        btnIniciar = new JButton("INICIAR");
        btnIniciar.setBackground(new Color(0, 140, 0));
        btnIniciar.setForeground(Color.WHITE);
        btnIniciar.setFont(new Font("Dialog", Font.BOLD, 12));
        btnIniciar.setOpaque(true);
        btnIniciar.setBorderPainted(false);
        btnIniciar.setFocusPainted(false);
        btnIniciar.addActionListener(e -> {
            btnIniciar.setBackground(new Color(160, 160, 160));
            iniciarServidor(false);
        });

        btnApagar = new JButton("APAGAR");
        btnApagar.setBackground(new Color(180, 0, 0));
        btnApagar.setForeground(Color.WHITE);
        btnApagar.setFont(new Font("Dialog", Font.BOLD, 12));
        btnApagar.setOpaque(true);
        btnApagar.setBorderPainted(false);
        btnApagar.setFocusPainted(false);
        btnApagar.setEnabled(false);
        btnApagar.addActionListener(e -> apagarManual());

        JLabel lblConfig = new JLabel(
                "<html><i>" +
                        "Watchdog: WatchdogSrv externo | " +
                        "Heartbeat: cada " + INTERVALO_HEARTBEAT + "s | " +
                        "Timeout heartbeat: " + TIMEOUT_HEARTBEAT + "s | " +
                        "Canal archivos: :" + PORT_ARCHIVOS +
                        "</i></html>"
        );
        lblConfig.setFont(new Font("Dialog", Font.PLAIN, 11));
        lblConfig.setForeground(new Color(100, 100, 100));

        JPanel panelInfo = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        panelInfo.add(titulo);
        panelInfo.add(lblEstado);

        JPanel panelContadores = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        panelContadores.add(lblClientes);
        panelContadores.add(lblCola);

        JPanel panelBotones = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        panelBotones.add(btnIniciar);
        panelBotones.add(btnApagar);

        JPanel panelTop = new JPanel(new BorderLayout());
        panelTop.add(panelInfo,    BorderLayout.WEST);
        panelTop.add(panelBotones, BorderLayout.EAST);

        panelNorte.add(panelTop,        BorderLayout.NORTH);
        panelNorte.add(panelContadores, BorderLayout.CENTER);
        panelNorte.add(lblConfig,       BorderLayout.SOUTH);

        // CENTRO
        logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
        logArea.setBackground(new Color(245, 245, 250));

        JScrollPane scroll = new JScrollPane(logArea);
        scroll.setBorder(BorderFactory.createTitledBorder("Log del servidor"));

        add(panelNorte, BorderLayout.NORTH);
        add(scroll,     BorderLayout.CENTER);
    }

    // ── CREAR DIRECTORIOS ─────────────────────────────────────
    private void crearDirectorios() {
        new File(DIR_LOGS).mkdirs();
        new File(DIR_ESTADO).mkdirs();
        new File(DIR_ARCHIVOS).mkdirs();
    }

    // ── INICIAR SERVIDOR ──────────────────────────────────────
    void iniciarServidor(boolean porWatchdog) {
        new Thread(() -> {
            try {
                serverSocket         = new ServerSocket(PORT);
                serverSocketArchivos = new ServerSocket(PORT_ARCHIVOS);
                activo.set(true);
                apagadoManual.set(false);

                actualizarEstado("⬤ Activo en :" + PORT, new Color(0, 150, 0));
                SwingUtilities.invokeLater(() -> {
                    btnIniciar.setEnabled(false);
                    btnIniciar.setBackground(new Color(160, 160, 160));
                    btnApagar.setEnabled(true);
                    btnApagar.setBackground(new Color(180, 0, 0));
                });

                iniciarHeartbeat();

                // Lanzar acceptor del canal de archivos en hilo aparte
                new Thread(this::aceptarArchivos).start();

                if (porWatchdog) {
                    logUI("---------------------------------------------------------------");
                    logUI("[WATCHDOG] Servidor reiniciado por watchdog externo.");
                    recuperarEstado();
                    logUI("---------------------------------------------------------------");
                } else {
                    logUI("---------------------------------------------------------------");
                    logUI("[INICIO] Servidor TCP activo en puerto " + PORT);
                    logUI("[CONFIG] Watchdog         : WatchdogSrv externo");
                    logUI("[CONFIG] Heartbeat        : cada " + INTERVALO_HEARTBEAT + "s");
                    logUI("[CONFIG] Timeout heartbeat: " + TIMEOUT_HEARTBEAT + "s");
                    logUI("[CONFIG] Canal archivos   : :" + PORT_ARCHIVOS);
                    logUI("[INFO]   Logs             : " + DIR_LOGS);
                    logUI("[INFO]   Estado           : " + DIR_ESTADO);
                    logUI("---------------------------------------------------------------");
                    cargarColaPersistente();
                }

                // Bucle de aceptación — canal de texto
                while (activo.get()) {
                    try {
                        Socket cliente = serverSocket.accept();
                        new ClientHandler(cliente).start();
                    } catch (SocketException ex) {
                        logUI("[CAIDA] ServerSocket cerrado: " + ex.getMessage());
                        break;
                    }
                }

                detenerHeartbeat();
                logUI("[SERVIDOR] Bucle de aceptación terminado.");

            } catch (BindException ex) {
                logUI("[ERROR] Puerto " + PORT + " ya está en uso.");
                actualizarEstado("⬤ Error de puerto", new Color(180, 0, 0));
                SwingUtilities.invokeLater(() -> {
                    btnIniciar.setEnabled(true);
                    btnIniciar.setBackground(new Color(0, 140, 0));
                });
            } catch (IOException ex) {
                logUI("[ERROR] No se pudo iniciar: " + ex.getMessage());
                actualizarEstado("⬤ Error", new Color(180, 0, 0));
                SwingUtilities.invokeLater(() -> {
                    btnIniciar.setEnabled(true);
                    btnIniciar.setBackground(new Color(0, 140, 0));
                });
            }
        }).start();
    }

    // ── ACCEPTOR DE ARCHIVOS (canal binario separado) ─────────

    private void aceptarArchivos() {
        while (activo.get()) {
            try {
                Socket sa = serverSocketArchivos.accept();
                new Thread(() -> recibirArchivoCanal(sa)).start();
            } catch (SocketException ex) {
                break; // servidor apagado
            } catch (IOException ex) {
                logUI("[ERROR ARCHIVO-CANAL] " + ex.getMessage());
            }
        }
    }

    private void recibirArchivoCanal(Socket sa) {
        try (sa) {
            InputStream rawIn = sa.getInputStream();
            // Leer la línea de metadatos manualmente (evitar BufferedReader que consume bytes)
            StringBuilder sbMeta = new StringBuilder();
            int b;
            while ((b = rawIn.read()) != -1) {
                if (b == '\n') break;
                if (b != '\r') sbMeta.append((char) b);
            }

            String[] p = sbMeta.toString().split("\\|", 4);
            if (p.length < 4) {
                logUI("[ERROR ARCHIVO-CANAL] Metadatos mal formados: " + sbMeta);
                return;
            }

            String remitente   = p[0].trim();
            String destino     = p[1].trim();
            String nomArchivo  = p[2].trim();
            long   tamanio;

            try {
                tamanio = Long.parseLong(p[3].trim());
            } catch (NumberFormatException ex) {
                logUI("[ERROR ARCHIVO-CANAL] Tamaño inválido: " + p[3]);
                return;
            }

            // Validaciones de seguridad en el servidor
            String nomLower = nomArchivo.toLowerCase();
            for (String ext : EXT_BLOQUEADAS) {
                if (nomLower.endsWith(ext)) {
                    logUI("[VALIDACION] Archivo rechazado por extension: " + nomArchivo);
                    return;
                }
            }
            if (nomArchivo.contains("..") || nomArchivo.contains("/") || nomArchivo.contains("\\")) {
                logUI("[VALIDACION] Nombre de archivo peligroso: " + nomArchivo);
                return;
            }
            if (tamanio > MAX_TAMANIO_ARCHIVO) {
                logUI("[VALIDACION] Archivo demasiado grande: " + tamanio + " bytes");
                return;
            }

            String sub = destino.equals("*") ? "publicos" : destino;
            File carpeta = new File(DIR_ARCHIVOS, sub);
            carpeta.mkdirs();
            File archivo = new File(carpeta, nomArchivo);

            try (FileOutputStream fos = new FileOutputStream(archivo)) {
                byte[] buf = new byte[4096];
                long recibidos = 0;
                while (recibidos < tamanio) {
                    int leidos = rawIn.read(buf, 0, (int) Math.min(buf.length, tamanio - recibidos));
                    if (leidos == -1) break;
                    fos.write(buf, 0, leidos);
                    recibidos += leidos;
                }
            }

            String aviso = "[ARCHIVO] " + remitente + " → " + archivo.getAbsolutePath();
            logUI(aviso);
            escribirLogArchivo(aviso);

            if (destino.equals("*")) {
                broadcast("SERVIDOR", remitente + " envió archivo '" + nomArchivo + "'");
            } else {
                ClientHandler target = clientes.get(destino);
                if (target != null) {
                    target.enviar("[ARCHIVO] De " + remitente + ": " + archivo.getAbsolutePath());
                } else {
                    encolarMensaje(destino, "[ARCHIVO pendiente] De " + remitente
                            + ": " + archivo.getAbsolutePath());
                    ClientHandler origen = clientes.get(remitente);
                    if (origen != null)
                        origen.enviar("SERVIDOR: '" + destino + "' offline. Aviso encolado.");
                }
            }

        } catch (IOException ex) {
            logUI("[ERROR ARCHIVO-CANAL] " + ex.getMessage());
        }
    }

    // ── APAGAR MANUAL ─────────────────────────────────────────
    /**
     * [FIX] ESCENARIO 2 — Apagado intencional:
     * Escribe el flag apagado_manual.flag ANTES de cerrar.
     * El WatchdogSrv detecta ese flag y NO reinicia el servidor.
     */
    private void apagarManual() {
        int ok = JOptionPane.showConfirmDialog(this,
                "¿Apagar el servidor?\nLos clientes ejecutarán su política de reconexión.\n"
                        + "El Watchdog NO reiniciará (apagado manual).",
                "Confirmar apagado", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (ok != JOptionPane.YES_OPTION) return;

        apagadoManual.set(true);
        activo.set(false);

        // ── Escribir flag ANTES de cerrar sockets ─────────────
        escribirFlagApagadoManual();

        guardarEstado();
        cerrarServerSocket();

        actualizarEstado("⬤ Apagado manualmente", Color.GRAY);
        SwingUtilities.invokeLater(() -> {
            btnIniciar.setEnabled(true);
            btnIniciar.setBackground(new Color(0, 140, 0));
            btnApagar.setEnabled(false);
        });

        logUI("---------------------------------------------------------------");
        logUI("[APAGADO MANUAL] Servidor detenido por el administrador.");
        logUI("[ESCENARIO 2] Flag escrito → Watchdog NO reiniciará.");
        logUI("[ESTADO] Cola y log guardados en: " + DIR_ESTADO);
        logUI("[INFO] Los clientes ejecutarán política de reconexión.");
        logUI("---------------------------------------------------------------");
    }

    /** Crea el archivo centinela que le dice al Watchdog que fue intencional. */
    private void escribirFlagApagadoManual() {
        try {
            File flag = new File(FLAG_APAGADO_MANUAL);
            flag.getParentFile().mkdirs();
            if (!flag.createNewFile() && !flag.exists()) {
                logUI("[WARN] No se pudo crear flag de apagado manual.");
            } else {
                logUI("[FLAG] apagado_manual.flag creado en " + DIR_ESTADO);
            }
        } catch (IOException ex) {
            logUI("[ERROR] No se pudo escribir flag: " + ex.getMessage());
        }
    }

    // ── HEARTBEAT ─────────────────────────────────────────────
    private void iniciarHeartbeat() {
        heartbeatScheduler = Executors.newScheduledThreadPool(1);
        heartbeatScheduler.scheduleAtFixedRate(() -> {
            if (clientes.isEmpty()) return;
            logUI("[HEARTBEAT] Enviando ping a " + clientes.size() + " cliente(s)...");
            for (ClientHandler c : new ArrayList<>(clientes.values())) {
                c.enviarHeartbeat();
            }
        }, INTERVALO_HEARTBEAT, INTERVALO_HEARTBEAT, TimeUnit.SECONDS);
    }

    private void detenerHeartbeat() {
        if (heartbeatScheduler != null && !heartbeatScheduler.isShutdown())
            heartbeatScheduler.shutdownNow();
    }

    // ── GUARDAR ESTADO ────────────────────────────────────────
    private void guardarEstado() {
        guardarColaPersistente();
        guardarClientesConectados();
    }

    private void guardarColaPersistente() {
        try (PrintWriter pw = new PrintWriter(new FileWriter(ARCHIVO_COLA))) {
            int total = 0;
            for (Map.Entry<String, Queue<String>> e : colaMensajes.entrySet()) {
                for (String msg : e.getValue()) {
                    pw.println(e.getKey() + "|" + msg);
                    total++;
                }
            }
            logUI("[ESTADO] Cola guardada: " + total + " mensaje(s) pendiente(s).");
        } catch (IOException ex) {
            logUI("[ERROR] No se pudo guardar la cola: " + ex.getMessage());
        }
    }

    private void guardarClientesConectados() {
        try (PrintWriter pw = new PrintWriter(new FileWriter(ARCHIVO_CLI))) {
            for (String nombre : clientes.keySet()) pw.println(nombre);
            logUI("[ESTADO] Clientes registrados: " + clientes.size());
        } catch (IOException ex) {
            logUI("[ERROR] No se pudo guardar clientes: " + ex.getMessage());
        }
    }

    // ── RECUPERAR ESTADO ──────────────────────────────────────
    private void recuperarEstado() {
        cargarColaPersistente();
        cargarClientesAnteriores();
        cargarLogDelDia();
    }

    private void cargarColaPersistente() {
        File f = new File(ARCHIVO_COLA);
        if (!f.exists()) { logUI("[ESTADO] No hay cola previa guardada."); return; }
        int cargados = 0;
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String linea;
            while ((linea = br.readLine()) != null) {
                int sep = linea.indexOf("|");
                if (sep < 0) continue;
                String dest = linea.substring(0, sep);
                String msg  = linea.substring(sep + 1);
                colaMensajes.computeIfAbsent(dest, k -> new LinkedList<>()).add(msg);
                cargados++;
            }
        } catch (IOException ex) {
            logUI("[ERROR] No se pudo cargar cola: " + ex.getMessage());
        }
        logUI("[RECUPERACIÓN] Cola restaurada: " + cargados + " mensaje(s) pendiente(s).");
        actualizarContadorCola();
    }

    private void cargarClientesAnteriores() {
        File f = new File(ARCHIVO_CLI);
        if (!f.exists()) { logUI("[ESTADO] No hay clientes previos registrados."); return; }
        List<String> nombres = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String linea;
            while ((linea = br.readLine()) != null)
                if (!linea.isBlank()) nombres.add(linea.trim());
        } catch (IOException ex) {
            logUI("[ERROR] No se pudo cargar clientes: " + ex.getMessage());
        }
        if (!nombres.isEmpty())
            logUI("[RECUPERACIÓN] Clientes previos: " + String.join(", ", nombres)
                    + " — reconectarán automáticamente.");
    }

    private void cargarLogDelDia() {
        String hoy = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        File f = new File(DIR_LOGS, "servidor_" + hoy + ".txt");
        if (!f.exists()) return;
        logUI("[RECUPERACIÓN] Log del día encontrado: " + f.getName());
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String linea;
            int leidas = 0;
            while ((linea = br.readLine()) != null && leidas < 10) {
                logUI("  " + linea); leidas++;
            }
            if (leidas == 10) logUI("  ... (ver archivo completo en " + f.getAbsolutePath() + ")");
        } catch (IOException ex) {
            logUI("[ERROR] No se pudo leer log: " + ex.getMessage());
        }
    }

    // ── LOG PERSISTENTE ───────────────────────────────────────
    private void escribirLogArchivo(String texto) {
        String hoy   = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        String ahora = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        File f = new File(DIR_LOGS, "servidor_" + hoy + ".txt");
        try (PrintWriter pw = new PrintWriter(new FileWriter(f, true))) {
            pw.println("[" + ahora + "] " + texto);
        } catch (IOException ignored) {}
    }

    // ── COLA DE MENSAJES ──────────────────────────────────────
    private void encolarMensaje(String destino, String mensaje) {
        colaMensajes.computeIfAbsent(destino, k -> new LinkedList<>()).add(mensaje);
        actualizarContadorCola();
        logUI("[COLA] Mensaje encolado para '" + destino + "' (offline).");
        guardarColaPersistente();
    }

    private void entregarColaPendiente(ClientHandler handler) {
        Queue<String> cola = colaMensajes.remove(handler.nombre);
        if (cola == null || cola.isEmpty()) return;
        logUI("[COLA] Entregando " + cola.size() + " mensaje(s) a " + handler.nombre);
        handler.enviar("SERVIDOR: Tienes " + cola.size() + " mensaje(s) pendiente(s):");
        for (String msg : cola) handler.enviar("  " + msg);
        actualizarContadorCola();
        guardarColaPersistente();
    }

    // ── BROADCAST ─────────────────────────────────────────────
    private void broadcast(String remitente, String texto) {
        String msg = "[" + remitente + "]: " + texto;
        clientes.values().forEach(c -> c.enviar(msg));
        logUI(msg);
        escribirLogArchivo(msg);
    }

    // ── PRIVADO ───────────────────────────────────────────────
    private void enviarPrivado(String remitente, String destino, String texto) {
        String msg = "[Privado de " + remitente + "]: " + texto;
        ClientHandler target = clientes.get(destino);
        if (target != null) {
            target.enviar(msg);
            ClientHandler origen = clientes.get(remitente);
            if (origen != null) origen.enviar("[Privado -> " + destino + "]: " + texto);
        } else {
            encolarMensaje(destino, msg);
            ClientHandler origen = clientes.get(remitente);
            if (origen != null)
                origen.enviar("SERVIDOR: '" + destino + "' está offline. Mensaje encolado.");
        }
        escribirLogArchivo("[PRIVADO] " + remitente + " -> " + destino + ": " + texto);
    }

    // ── NOMBRE ÚNICO (thread-safe) ────────────────────────────
    /** [FIX] synchronized para evitar condición de carrera cuando varios
     *  clientes se conectan simultáneamente con el mismo nombre. */
    private synchronized String generarNombre(String base) {
        if (base == null || base.isBlank()) base = "cliente";
        if (!clientes.containsKey(base)) return base;
        int i = 1;
        while (clientes.containsKey(base + i)) i++;
        return base + i;
    }

    // ── CERRAR ────────────────────────────────────────────────
    private void cerrarServerSocket() {
        try { if (serverSocket         != null && !serverSocket.isClosed())         serverSocket.close(); }
        catch (IOException ignored) {}
        try { if (serverSocketArchivos != null && !serverSocketArchivos.isClosed()) serverSocketArchivos.close(); }
        catch (IOException ignored) {}
    }

    // ── LOG UI + ARCHIVO ──────────────────────────────────────
    private void logUI(String texto) {
        SwingUtilities.invokeLater(() -> {
            logArea.append(texto + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
        escribirLogArchivo(texto);
    }

    private void actualizarEstado(String texto, Color color) {
        SwingUtilities.invokeLater(() -> {
            lblEstado.setText(texto);
            lblEstado.setForeground(color);
        });
    }

    private void actualizarContador() {
        SwingUtilities.invokeLater(() -> lblClientes.setText("Clientes: " + clientes.size()));
    }

    private void actualizarContadorCola() {
        int total = colaMensajes.values().stream().mapToInt(Queue::size).sum();
        SwingUtilities.invokeLater(() -> lblCola.setText("Cola: " + total + " mensajes"));
    }

    // ── CLIENT HANDLER ────────────────────────────────────────
    private class ClientHandler extends Thread {
        private final Socket         socket;
        private       PrintWriter    out;
        private       BufferedReader in;
        String                       nombre;

        private volatile long ultimoPong = System.currentTimeMillis();

        ClientHandler(Socket s) { this.socket = s; }

        @Override
        public void run() {
            try {
                out = new PrintWriter(socket.getOutputStream(), true);
                in  = new BufferedReader(new InputStreamReader(socket.getInputStream()));

                String primera = in.readLine();
                if (primera == null) return;
                String nombreSolicitado = primera.replace("NOMBRE:", "").trim();

                // VALIDACION: limite de clientes
                if (clientes.size() >= MAX_CLIENTES) {
                    enviar("SERVIDOR: Servidor lleno. Maximo " + MAX_CLIENTES + " clientes.");
                    logUI("[VALIDACION] Conexion rechazada — limite de " + MAX_CLIENTES + " alcanzado.");
                    return;
                }

                // VALIDACION: caracteres permitidos
                if (!nombreSolicitado.matches(REGEX_NOMBRE)) {
                    enviar("SERVIDOR: Nombre invalido. Solo letras, numeros y _ (2-20 caracteres).");
                    logUI("[VALIDACION] Nombre rechazado: '" + nombreSolicitado + "'");
                    return;
                }

                // VALIDACION: palabras reservadas
                for (String reservada : PALABRAS_RESERVADAS) {
                    if (nombreSolicitado.equalsIgnoreCase(reservada)) {
                        enviar("SERVIDOR: El nombre '" + nombreSolicitado + "' es reservado.");
                        logUI("[VALIDACION] Nombre rechazado: '" + nombreSolicitado + "' — reservado.");
                        return;
                    }
                }

                nombre = generarNombre(nombreSolicitado);
                clientes.put(nombre, this);
                actualizarContador();

                enviar("SERVIDOR: Bienvenido " + nombre);
                broadcast("SERVIDOR", nombre + " se ha conectado");
                logUI("[+] Conectado: " + nombre + " [" + socket.getInetAddress() + "]");
                escribirLogArchivo("[CONEXION] " + nombre);

                entregarColaPendiente(this);

                String linea;
                while ((linea = in.readLine()) != null) {

                    if (linea.trim().isEmpty()) continue;

                    if (linea.equals("PONG")) {
                        ultimoPong = System.currentTimeMillis();
                        logUI("[HEARTBEAT] Pong recibido de " + nombre);
                        continue;
                    }

                    // [FIX] El header ARCHIVO: ya no llega por aquí con bytes binarios.
                    // Ahora es solo una notificación informativa — el archivo llega
                    // por PORT_ARCHIVOS en aceptarArchivos() / recibirArchivoCanal().
                    if (linea.startsWith("ARCHIVO:")) {
                        logUI("[ARCHIVO] Notificación recibida de " + nombre + ": " + linea);
                        // No hay bytes binarios aquí; la transferencia real es por PORT_ARCHIVOS.
                        continue;
                    }

                    if (linea.equals("/users")) {
                        enviar("Usuarios: " + String.join(", ", clientes.keySet()));
                    } else if (linea.equals("/salir")) {
                        break;
                    } else if (linea.startsWith("/msg ")) {
                        String[] parts = linea.split(" ", 3);
                        if (parts.length == 3) enviarPrivado(nombre, parts[1], parts[2]);
                    } else {
                        broadcast(nombre, linea);
                    }
                }

            } catch (SocketException ex) {
                logUI("[RED] " + (nombre != null ? nombre : "desconocido")
                        + " perdió la conexión: " + ex.getMessage());
            } catch (IOException ex) {
                logUI("[ERROR] " + ex.getMessage());
            } finally {
                if (nombre != null) {
                    clientes.remove(nombre);
                    actualizarContador();
                    broadcast("SERVIDOR", nombre + " se ha desconectado");
                    escribirLogArchivo("[DESCONEXIÓN] " + nombre);
                    logUI("[-] Desconectado: " + nombre);
                }
                try { socket.close(); } catch (IOException ignored) {}
            }
        }

        // ── HEARTBEAT ─────────────────────────────────────────
        void enviarHeartbeat() {
            try {
                enviar("PING");
                new Thread(() -> {
                    try {
                        Thread.sleep(TIMEOUT_HEARTBEAT * 1000L);
                        long ahora = System.currentTimeMillis();
                        if (ahora - ultimoPong > TIMEOUT_HEARTBEAT * 1000L) {
                            logUI("[HEARTBEAT] Sin respuesta de " + nombre + " — desconectando.");
                            try { socket.close(); } catch (IOException ignored) {}
                        }
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }).start();
            } catch (Exception ex) {
                logUI("[HEARTBEAT] Error enviando ping a " + nombre + ": " + ex.getMessage());
            }
        }

        void enviar(String msg) { if (out != null) out.println(msg); }
    }

    public static void main(String[] args) {
        // Si el Watchdog lanza el proceso con --autostart, el servidor
        // llama a iniciarServidor() automaticamente sin esperar clic del usuario
        boolean autostart = args.length > 0 && args[0].equals("--autostart");

        SwingUtilities.invokeLater(() -> {
            PrincipalSrv srv = new PrincipalSrv();
            srv.setVisible(true);
            if (autostart) {
                srv.logUI("[WATCHDOG] Arranque automatico detectado — iniciando servidor...");
                srv.iniciarServidor(true);
            }
        });
    }
}