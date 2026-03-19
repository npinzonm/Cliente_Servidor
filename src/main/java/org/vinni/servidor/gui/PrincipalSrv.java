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
 * Servidor TCP con políticas completas:
 *
 * REINICIO:
 *   - Watchdog automático ante caída inesperada
 *   - Apagado manual sin watchdog
 *   - Recuperación de estado al reiniciar (log, cola, clientes)
 *
 * HEARTBEAT:
 *   - Ping cada INTERVALO_HEARTBEAT segundos a cada cliente
 *   - Si no responde en TIMEOUT_HEARTBEAT segundos → desconexión limpia
 *
 * LOGS PERSISTENTES:
 *   - Cada evento se escribe en ~/ServidorTCP/logs/servidor_FECHA.txt
 *   - Al reiniciar se carga el log del día actual
 *
 * COLA DE MENSAJES:
 *   - Mensajes para clientes desconectados se guardan en cola
 *   - Al reconectarse se entregan automáticamente
 *   - Cola persistente en ~/ServidorTCP/estado/cola_mensajes.txt
 *
 * Author: Vinni 2024 | Nathalie Pinzón 2026
 */
public class PrincipalSrv extends JFrame {

    // ── Configuración ─────────────────────────────────────────
    private static final int    PORT                = 12345;
    private static final int    DELAY_REINICIO      = 5;    // segundos antes de reiniciar
    private static final int    MAX_REINICIOS       = 3;    // máx reinicios watchdog
    private static final int    INTERVALO_HEARTBEAT = 30;   // segundos entre pings
    private static final int    TIMEOUT_HEARTBEAT   = 10;   // segundos esperando pong

    // ── Validaciones ──────────────────────────────────────────
    private static final int      MAX_CLIENTES        = 10;  // limite de clientes simultáneos
    private static final String   REGEX_NOMBRE        = "^[a-zA-Z0-9_]{2,20}$";
    private static final String[] PALABRAS_RESERVADAS = {"SERVIDOR", "TODOS", "*"};
    private static final String[] EXT_BLOQUEADAS      = {".exe", ".bat", ".sh", ".cmd", ".msi"};
    private static final long     MAX_TAMANIO_ARCHIVO = 10 * 1024 * 1024; // 10 MB

    // ── Rutas de persistencia ─────────────────────────────────
    private static final String BASE_DIR    = System.getProperty("user.home") + File.separator + "ServidorTCP";
    private static final String DIR_LOGS    = BASE_DIR + File.separator + "logs";
    private static final String DIR_ESTADO  = BASE_DIR + File.separator + "estado";
    private static final String DIR_ARCHIVOS= BASE_DIR + File.separator + "archivos";
    private static final String ARCHIVO_COLA= DIR_ESTADO + File.separator + "cola_mensajes.txt";
    private static final String ARCHIVO_CLI = DIR_ESTADO + File.separator + "clientes.txt";

    // ── Estado del servidor ───────────────────────────────────
    private ServerSocket        serverSocket;
    private final AtomicBoolean activo        = new AtomicBoolean(false);
    private final AtomicBoolean apagadoManual = new AtomicBoolean(false);
    private final AtomicInteger reinicios     = new AtomicInteger(0);

    /** Clientes actualmente conectados: nombre → handler */
    private final Map<String, ClientHandler> clientes = new ConcurrentHashMap<>();

    /**
     * Cola de mensajes persistente.
     * Clave: nombre del destinatario
     * Valor: lista de mensajes pendientes
     */
    private final Map<String, Queue<String>> colaMensajes = new ConcurrentHashMap<>();

    /** Scheduler para heartbeat */
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

        // Interceptar cierre de ventana con X
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (activo.get()) {
                    // NO tocar activo aquí — el hilo del servidor lo detecta por SocketException
                    logUI("[VENTANA] Ventana cerrada — el watchdog tomará control...");
                    setVisible(false); // ocultar ventana, JVM sigue viva
                    cerrarServerSocket(); // lanza SocketException en accept() → activa watchdog
                } else {
                    System.exit(0);
                }
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

        lblEstado   = new JLabel("Detenido");
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
            btnIniciar.setBackground(new Color(160, 160, 160)); // gris al hacer clic
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
                        "Watchdog: " + MAX_REINICIOS + " reinicios | " +
                        "Reinicio en: " + DELAY_REINICIO + "s | " +
                        "Heartbeat: cada " + INTERVALO_HEARTBEAT + "s | " +
                        "Timeout heartbeat: " + TIMEOUT_HEARTBEAT + "s" +
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
    private void iniciarServidor(boolean porWatchdog) {
        new Thread(() -> {
            try {
                serverSocket = new ServerSocket(PORT);
                activo.set(true);
                apagadoManual.set(false);

                actualizarEstado("Activo en :" + PORT, new Color(0, 150, 0));
                SwingUtilities.invokeLater(() -> setVisible(true)); // mostrar ventana si estaba oculta
                SwingUtilities.invokeLater(() -> {
                    btnIniciar.setEnabled(false);
                    btnIniciar.setBackground(new Color(160, 160, 160)); // gris deshabilitado
                    btnApagar.setEnabled(true);
                    btnApagar.setBackground(new Color(180, 0, 0));      // rojo activo
                });

                // Iniciar heartbeat scheduler
                iniciarHeartbeat();

                if (porWatchdog) {
                    logUI("---------------------------------------------------------------------------------------");
                    logUI("[WATCHDOG] Servidor reiniciado automáticamente ("
                            + reinicios.get() + "/" + MAX_REINICIOS + ")");
                    recuperarEstado(); // restaurar cola, log, clientes
                    logUI("---------------------------------------------------------------------------------------");
                } else {
                    logUI("---------------------------------------------------------------------------------------");
                    logUI("[INICIO] Servidor TCP activo en puerto " + PORT);
                    logUI("[CONFIG] Watchdog        : " + MAX_REINICIOS + " reinicios máx");
                    logUI("[CONFIG] Delay reinicio  : " + DELAY_REINICIO + "s");
                    logUI("[CONFIG] Heartbeat       : cada " + INTERVALO_HEARTBEAT + "s");
                    logUI("[CONFIG] Timeout heartbeat: " + TIMEOUT_HEARTBEAT + "s");
                    logUI("[INFO]   Logs            : " + DIR_LOGS);
                    logUI("[INFO]   Estado          : " + DIR_ESTADO);
                    logUI("---------------------------------------------------------------------------------------");
                    cargarColaPersistente(); // cargar cola guardada
                }

                // Bucle de aceptación
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

                if (!apagadoManual.get()) activarWatchdog();

            } catch (BindException ex) {
                logUI("[ERROR] Puerto " + PORT + " ya está en uso.");
                actualizarEstado("Error de puerto", new Color(180, 0, 0));
                SwingUtilities.invokeLater(() -> btnIniciar.setEnabled(true));
            } catch (IOException ex) {
                logUI("[ERROR] No se pudo iniciar: " + ex.getMessage());
                actualizarEstado("Error", new Color(180, 0, 0));
                SwingUtilities.invokeLater(() -> btnIniciar.setEnabled(true));
            }
        }).start();
    }

    // ── APAGAR MANUAL ─────────────────────────────────────────
    private void apagarManual() {
        int ok = JOptionPane.showConfirmDialog(this,
                "¿Apagar el servidor?\nLos clientes ejecutarán su política de reconexión.",
                "Confirmar apagado", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (ok != JOptionPane.YES_OPTION) return;

        apagadoManual.set(true);
        activo.set(false);
        guardarEstado();
        cerrarServerSocket();

        actualizarEstado("Apagado manualmente", Color.GRAY);
        SwingUtilities.invokeLater(() -> {
            btnIniciar.setEnabled(true);
            btnApagar.setEnabled(false);
        });

        logUI("---------------------------------------------------------------------------------------");
        logUI("[APAGADO MANUAL] Servidor detenido por el administrador.");
        logUI("[ESCENARIO 2] Watchdog NO actuará — apagado intencional.");
        logUI("[ESTADO] Cola y log guardados en: " + DIR_ESTADO);
        logUI("[INFO] Los clientes ejecutarán política de reconexión.");
        logUI("---------------------------------------------------------------------------------------");
    }

    // ── WATCHDOG ──────────────────────────────────────────────
    private void activarWatchdog() {
        if (reinicios.get() >= MAX_REINICIOS) {
            logUI("---------------------------------------------------------------------------------------");
            logUI("[WATCHDOG] Límite de reinicios alcanzado (" + MAX_REINICIOS + "/" + MAX_REINICIOS + ").");
            logUI("[WATCHDOG] Reinicia manualmente con el botón INICIAR.");
            logUI("---------------------------------------------------------------------------------------");
            actualizarEstado("Watchdog agotado", new Color(180, 0, 0));
            SwingUtilities.invokeLater(() -> {
                btnIniciar.setEnabled(true);
                btnApagar.setEnabled(false);
            });
            return;
        }

        reinicios.incrementAndGet();
        activo.set(false);
        guardarEstado();

        logUI("---------------------------------------------------------------------------------------");
        logUI("[WATCHDOG] Caída detectada. Reinicio " + reinicios.get() + "/" + MAX_REINICIOS);
        logUI("[WATCHDOG] Estado guardado. Reiniciando en " + DELAY_REINICIO + "s...");
        actualizarEstado("Watchdog: reiniciando...", new Color(180, 100, 0));

        new Thread(() -> {
            for (int s = DELAY_REINICIO; s >= 1; s--) {
                logUI("[WATCHDOG] Reiniciando en " + s + "s...");
                try { Thread.sleep(1000); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
            }
            logUI("[WATCHDOG] Levantando servidor...");
            iniciarServidor(true);
        }).start();
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
            logUI("[RECUPERACIÓN] Clientes previos registrados: " + String.join(", ", nombres)
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
        String hoy = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
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
        logUI("[COLA] Entregando " + cola.size() + " mensaje(s) pendiente(s) a " + handler.nombre);
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
            clientes.get(remitente).enviar("[Privado -> " + destino + "]: " + texto);
        } else {
            // Destinatario offline → encolar
            encolarMensaje(destino, msg);
            ClientHandler origen = clientes.get(remitente);
            if (origen != null)
                origen.enviar("SERVIDOR: '" + destino + "' está offline. Mensaje encolado.");
        }
        escribirLogArchivo("[PRIVADO] " + remitente + " -> " + destino + ": " + texto);
    }

    // ── NOMBRE ÚNICO ──────────────────────────────────────────
    private String generarNombre(String base) {
        if (base == null || base.isBlank()) base = "cliente";
        if (!clientes.containsKey(base)) return base;
        int i = 1;
        while (clientes.containsKey(base + i)) i++;
        return base + i;
    }

    // ── CERRAR ────────────────────────────────────────────────
    private void cerrarServerSocket() {
        try { if (serverSocket != null && !serverSocket.isClosed()) serverSocket.close(); }
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
        private final Socket           socket;
        private       PrintWriter      out;
        private       BufferedReader   in;
        private       DataInputStream  dataIn;
        String                         nombre;

        /** Para heartbeat: timestamp del último pong recibido */
        private volatile long ultimoPong = System.currentTimeMillis();

        ClientHandler(Socket s) { this.socket = s; }

        @Override
        public void run() {
            try {
                out    = new PrintWriter(socket.getOutputStream(), true);
                in     = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                dataIn = new DataInputStream(socket.getInputStream());

                String primera = in.readLine();
                if (primera == null) return;
                String nombreSolicitado = primera.replace("NOMBRE:", "").trim();

                // VALIDACION: limite de clientes
                if (clientes.size() >= MAX_CLIENTES) {
                    enviar("SERVIDOR: Servidor lleno. Maximo " + MAX_CLIENTES + " clientes permitidos.");
                    logUI("[VALIDACION] Conexion rechazada — limite de " + MAX_CLIENTES + " clientes alcanzado.");
                    return;
                }

                // VALIDACION: caracteres permitidos en el nombre
                if (!nombreSolicitado.matches(REGEX_NOMBRE)) {
                    enviar("SERVIDOR: Nombre invalido. Solo letras, numeros y _ (2-20 caracteres).");
                    logUI("[VALIDACION] Nombre rechazado: '" + nombreSolicitado + "' — caracteres no permitidos.");
                    return;
                }

                // VALIDACION: palabras reservadas
                for (String reservada : PALABRAS_RESERVADAS) {
                    if (nombreSolicitado.equalsIgnoreCase(reservada)) {
                        enviar("SERVIDOR: El nombre '" + nombreSolicitado + "' es una palabra reservada.");
                        logUI("[VALIDACION] Nombre rechazado: '" + nombreSolicitado + "' — palabra reservada.");
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

                // Entregar mensajes pendientes de la cola
                entregarColaPendiente(this);

                String linea;
                while ((linea = in.readLine()) != null) {

                    // VALIDACION: mensaje vacio
                    if (linea.trim().isEmpty()) {
                        logUI("[VALIDACION] Mensaje vacio ignorado de " + nombre);
                        continue;
                    }

                    if (linea.equals("PONG")) {
                        // Respuesta al heartbeat
                        ultimoPong = System.currentTimeMillis();
                        logUI("[HEARTBEAT] Pong recibido de " + nombre);
                        continue;
                    }
                    if (linea.startsWith("ARCHIVO:")) {
                        recibirArchivo(linea);
                    } else if (linea.equals("/users")) {
                        enviar("Usuarios: " + String.join(", ", clientes.keySet()));
                    } else if (linea.equals("/salir")) {
                        break;
                    } else if (linea.startsWith("/msg ")) {
                        String[] p = linea.split(" ", 3);
                        if (p.length == 3) enviarPrivado(nombre, p[1], p[2]);
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
                // Verificar en hilo separado si llega el PONG
                new Thread(() -> {
                    try {
                        Thread.sleep(TIMEOUT_HEARTBEAT * 1000L);
                        long ahora = System.currentTimeMillis();
                        if (ahora - ultimoPong > TIMEOUT_HEARTBEAT * 1000L) {
                            logUI("[HEARTBEAT] Sin respuesta de " + nombre
                                    + " — desconectando limpiamente.");
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

        // ── RECIBIR ARCHIVO ───────────────────────────────────
        private void recibirArchivo(String header) throws IOException {
            String[] p     = header.split(":", 4);
            String destino = p[1];
            String nomArchivo = p[2];
            long   tamaño  = Long.parseLong(p[3]);

            String sub = destino.equals("*") ? "publicos" : destino;
            File carpeta = new File(DIR_ARCHIVOS, sub);
            carpeta.mkdirs();
            File archivo = new File(carpeta, nomArchivo);

            try (FileOutputStream fos = new FileOutputStream(archivo)) {
                byte[] buf = new byte[4096];
                long recibidos = 0;
                while (recibidos < tamaño) {
                    int leidos = dataIn.read(buf, 0,
                            (int) Math.min(buf.length, tamaño - recibidos));
                    if (leidos == -1) break;
                    fos.write(buf, 0, leidos);
                    recibidos += leidos;
                }
            }

            String aviso = "[ARCHIVO] " + nombre + " → " + archivo.getAbsolutePath();
            logUI(aviso);
            escribirLogArchivo(aviso);

            if (destino.equals("*")) {
                broadcast("SERVIDOR", nombre + " envió archivo '" + nomArchivo + "'");
            } else {
                ClientHandler target = clientes.get(destino);
                if (target != null) {
                    target.enviar("[ARCHIVO] De " + nombre + ": " + archivo.getAbsolutePath());
                } else {
                    encolarMensaje(destino, "[ARCHIVO pendiente] De " + nombre
                            + ": " + archivo.getAbsolutePath());
                    enviar("SERVIDOR: '" + destino + "' offline. Aviso encolado.");
                }
            }
        }

        void enviar(String msg) { if (out != null) out.println(msg); }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new PrincipalSrv().setVisible(true));
    }
}