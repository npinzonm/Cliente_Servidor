package org.vinni.balanceador;

import org.vinni.config.Politicas;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * BalanceadorTCP.java
 *
 * Balanceador de carga TCP con modo Hibrido:
 *   - Escucha en BAL_PUERTO_ENTRADA (9000)
 *   - Recibe todas las conexiones de clientes
 *   - Selecciona el backend con Least Connections
 *   - Actua como proxy bidireccional entre cliente y backend
 *   - Sondea periodicamente la salud de cada backend
 *   - Excluye backends caidos del pool automaticamente
 *   - Re-incorpora backends recuperados cuando superan BAL_EXITOS_PARA_ALTA
 *
 * Flujo de una conexion:
 *   Cliente → BalanceadorTCP:9000
 *     → seleccionarBackend() por Least Connections
 *     → ProxyBidireccional (dos hilos: cliente→backend y backend→cliente)
 *     → liberarConexion() al desconectar
 *
 * Author: Nathalie Pinzon 2026
 */
public class BalanceadorTCP extends JFrame {

    // -- Pool de backends -----------------------------------------
    private final List<ServidorBackend> backends = new ArrayList<>();

    // -- Estado ---------------------------------------------------
    private ServerSocket             serverSocket;
    private final AtomicBoolean      activo         = new AtomicBoolean(false);
    private final AtomicInteger      clientesActivos= new AtomicInteger(0);
    private ScheduledExecutorService sondeoScheduler;

    // -- GUI ------------------------------------------------------
    private JTextArea  logArea;
    private JLabel     lblEstado;
    private JLabel     lblClientes;
    private JLabel     lblBackends;
    private JButton    btnIniciar;
    private JButton    btnDetener;
    private JPanel     panelBackends;

    // -- Mapa de labels de backends para actualizar la UI ---------
    private final Map<String, JLabel> labelsBackend = new LinkedHashMap<>();

    public BalanceadorTCP() {
        inicializarBackends();
        initComponents();
    }

    // -- INICIALIZAR POOL -----------------------------------------
    private void inicializarBackends() {
        for (int puerto : Politicas.BAL_PUERTOS_BACKEND) {
            backends.add(new ServidorBackend(Politicas.BAL_HOST_BACKEND, puerto));
        }
    }

    // -- INIT GUI -------------------------------------------------
    private void initComponents() {
        setTitle("Balanceador TCP — Puerto " + Politicas.BAL_PUERTO_ENTRADA);
        setSize(780, 620);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout(8, 8));

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                detener();
                System.exit(0);
            }
        });

        add(construirPanelNorte(), BorderLayout.NORTH);
        add(construirPanelCentro(), BorderLayout.CENTER);
    }

    private JPanel construirPanelNorte() {
        JPanel panel = new JPanel(new BorderLayout(8, 6));
        panel.setBorder(BorderFactory.createEmptyBorder(12, 16, 6, 16));

        // Fila titulo + botones
        JLabel titulo = new JLabel("BALANCEADOR TCP  —  Least Connections");
        titulo.setFont(new Font("Dialog", Font.BOLD, 14));
        titulo.setForeground(new Color(0, 80, 160));

        btnIniciar = new JButton("INICIAR");
        estilizarBoton(btnIniciar, new Color(0, 140, 0));
        btnIniciar.addActionListener(e -> iniciar());

        btnDetener = new JButton("DETENER");
        estilizarBoton(btnDetener, new Color(180, 0, 0));
        btnDetener.setEnabled(false);
        btnDetener.addActionListener(e -> detener());

        JPanel filaBotones = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        filaBotones.add(btnIniciar);
        filaBotones.add(btnDetener);

        JPanel filaTitulo = new JPanel(new BorderLayout());
        filaTitulo.add(titulo,      BorderLayout.WEST);
        filaTitulo.add(filaBotones, BorderLayout.EAST);

        // Fila de estado
        lblEstado   = new JLabel("Detenido");
        lblEstado.setFont(new Font("Dialog", Font.BOLD, 12));
        lblEstado.setForeground(Color.GRAY);

        lblClientes = new JLabel("Clientes activos: 0");
        lblClientes.setFont(new Font("Dialog", Font.PLAIN, 12));

        lblBackends = new JLabel("Backends: 0/" + backends.size() + " disponibles");
        lblBackends.setFont(new Font("Dialog", Font.PLAIN, 12));
        lblBackends.setForeground(new Color(0, 80, 160));

        JPanel filaEstado = new JPanel(new FlowLayout(FlowLayout.LEFT, 16, 0));
        filaEstado.add(lblEstado);
        filaEstado.add(lblClientes);
        filaEstado.add(lblBackends);

        // Panel de backends (grid de indicadores)
        panelBackends = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 4));
        panelBackends.setBorder(BorderFactory.createTitledBorder("Pool de backends"));

        for (ServidorBackend b : backends) {
            JLabel lbl = new JLabel(formatearLabelBackend(b));
            lbl.setFont(new Font("Monospaced", Font.PLAIN, 11));
            lbl.setForeground(Color.GRAY);
            lbl.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(new Color(200, 200, 200)),
                    BorderFactory.createEmptyBorder(3, 8, 3, 8)
            ));
            labelsBackend.put(b.getId(), lbl);
            panelBackends.add(lbl);
        }

        // Info de politica
        JLabel lblPolitica = new JLabel(
                "<html><i>Algoritmo: Least Connections" +
                        " | Sondeo: cada " + Politicas.BAL_INTERVALO_SONDEO + "s" +
                        " | Fallos para caida: " + Politicas.BAL_FALLOS_PARA_CAIDA +
                        " | Exitos para alta: " + Politicas.BAL_EXITOS_PARA_ALTA +
                        " | Puerto entrada: " + Politicas.BAL_PUERTO_ENTRADA +
                        "</i></html>"
        );
        lblPolitica.setFont(new Font("Dialog", Font.PLAIN, 11));
        lblPolitica.setForeground(new Color(100, 100, 100));

        panel.add(filaTitulo,    BorderLayout.NORTH);
        panel.add(filaEstado,    BorderLayout.CENTER);
        panel.add(panelBackends, BorderLayout.SOUTH);

        JPanel wrapper = new JPanel(new BorderLayout(0, 4));
        wrapper.add(panel,       BorderLayout.CENTER);
        wrapper.add(lblPolitica, BorderLayout.SOUTH);
        wrapper.setBorder(BorderFactory.createEmptyBorder(0, 16, 4, 16));
        return wrapper;
    }

    private JPanel construirPanelCentro() {
        logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
        logArea.setBackground(new Color(245, 245, 250));
        logArea.setLineWrap(true);

        JScrollPane scroll = new JScrollPane(logArea);
        scroll.setBorder(BorderFactory.createTitledBorder("Log del balanceador"));

        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(0, 16, 12, 16));
        panel.add(scroll);
        return panel;
    }

    private void estilizarBoton(JButton btn, Color color) {
        btn.setBackground(color);
        btn.setForeground(Color.WHITE);
        btn.setFont(new Font("Dialog", Font.BOLD, 11));
        btn.setOpaque(true);
        btn.setBorderPainted(false);
        btn.setFocusPainted(false);
    }

    // -- INICIAR --------------------------------------------------
    private void iniciar() {
        if (activo.get()) return;
        btnIniciar.setEnabled(false);
        btnIniciar.setBackground(new Color(160, 160, 160));

        new Thread(() -> {
            try {
                serverSocket = new ServerSocket(Politicas.BAL_PUERTO_ENTRADA);
                activo.set(true);

                actualizarEstado("Activo en :" + Politicas.BAL_PUERTO_ENTRADA,
                        new Color(0, 150, 0));
                SwingUtilities.invokeLater(() -> btnDetener.setEnabled(true));

                log("=======================================================");
                log("[BAL] Balanceador iniciado en puerto "
                        + Politicas.BAL_PUERTO_ENTRADA);
                log("[BAL] Backends configurados: " + backends.size());
                for (ServidorBackend b : backends)
                    log("[BAL]   - " + b.getId());
                log("[BAL] Algoritmo: Least Connections");
                log("[BAL] Sondeo de salud: cada " + Politicas.BAL_INTERVALO_SONDEO + "s");
                log("=======================================================");

                iniciarSondeoSalud();

                // Bucle de aceptacion
                while (activo.get()) {
                    try {
                        Socket clienteSocket = serverSocket.accept();
                        manejarNuevaConexion(clienteSocket);
                    } catch (SocketException ex) {
                        if (activo.get())
                            log("[BAL] Error en accept: " + ex.getMessage());
                        break;
                    }
                }

            } catch (BindException ex) {
                log("[ERROR] Puerto " + Politicas.BAL_PUERTO_ENTRADA + " ya esta en uso.");
                actualizarEstado("Error de puerto", new Color(180, 0, 0));
                SwingUtilities.invokeLater(() -> {
                    btnIniciar.setEnabled(true);
                    btnIniciar.setBackground(new Color(0, 140, 0));
                });
            } catch (IOException ex) {
                log("[ERROR] " + ex.getMessage());
                actualizarEstado("Error", new Color(180, 0, 0));
                SwingUtilities.invokeLater(() -> {
                    btnIniciar.setEnabled(true);
                    btnIniciar.setBackground(new Color(0, 140, 0));
                });
            }
        }).start();
    }

    // -- DETENER --------------------------------------------------
    private void detener() {
        activo.set(false);
        detenerSondeo();
        try { if (serverSocket != null && !serverSocket.isClosed())
            serverSocket.close(); }
        catch (IOException ignored) {}

        actualizarEstado("Detenido", Color.GRAY);
        SwingUtilities.invokeLater(() -> {
            btnIniciar.setEnabled(true);
            btnIniciar.setBackground(new Color(0, 140, 0));
            btnDetener.setEnabled(false);
        });
        log("[BAL] Balanceador detenido.");
    }

    // -- NUEVA CONEXION -------------------------------------------
    private void manejarNuevaConexion(Socket clienteSocket) {
        if (clientesActivos.get() >= Politicas.BAL_MAX_CLIENTES) {
            log("[BAL] Limite de clientes alcanzado ("
                    + Politicas.BAL_MAX_CLIENTES + ") — conexion rechazada.");
            try { clienteSocket.close(); } catch (IOException ignored) {}
            return;
        }

        ServidorBackend backend = seleccionarBackend();

        if (backend == null) {
            log("[BAL] Sin backends disponibles — conexion rechazada: "
                    + clienteSocket.getInetAddress());
            try {
                PrintWriter pw = new PrintWriter(clienteSocket.getOutputStream(), true);
                pw.println("SERVIDOR: Sin servidores disponibles. Intente mas tarde.");
                clienteSocket.close();
            } catch (IOException ignored) {}
            return;
        }

        // Conectar al backend elegido
        try {
            Socket backendSocket = new Socket();
            backendSocket.connect(
                    new InetSocketAddress(backend.getHost(), backend.getPuerto()),
                    Politicas.BAL_TIMEOUT_BACKEND);

            backend.registrarConexion();
            int activos = clientesActivos.incrementAndGet();
            actualizarLabelBackend(backend);
            actualizarContadorClientes(activos);

            log("[BAL] Cliente " + clienteSocket.getInetAddress()
                    + " → " + backend.getId()
                    + " (activas en backend: " + backend.getConexionesActivas() + ")");

            // Lanzar proxy bidireccional
            new ProxyBidireccional(clienteSocket, backendSocket, backend).start();

        } catch (IOException ex) {
            log("[BAL] No se pudo conectar al backend " + backend.getId()
                    + ": " + ex.getMessage());
            backend.marcarCaido();
            actualizarLabelBackend(backend);

            // Reintentar con otro backend disponible
            ServidorBackend alternativo = seleccionarBackend();
            if (alternativo != null && alternativo != backend) {
                log("[BAL] Reintentando con backend alternativo: " + alternativo.getId());
                manejarNuevaConexion(clienteSocket); // recursa una vez
            } else {
                try {
                    PrintWriter pw = new PrintWriter(clienteSocket.getOutputStream(), true);
                    pw.println("SERVIDOR: No hay backends disponibles en este momento.");
                    clienteSocket.close();
                } catch (IOException ignored) {}
            }
        }
    }

    // -- LEAST CONNECTIONS ----------------------------------------
    /**
     * Selecciona el backend disponible con menos conexiones activas.
     * Si hay empate selecciona el primero en la lista (orden de configuracion).
     * Retorna null si no hay ningun backend disponible.
     */
    private synchronized ServidorBackend seleccionarBackend() {
        ServidorBackend elegido = null;
        int menorCarga = Integer.MAX_VALUE;

        for (ServidorBackend b : backends) {
            if (b.isDisponible()) {
                int carga = b.getConexionesActivas();
                if (carga < menorCarga) {
                    menorCarga = carga;
                    elegido    = b;
                }
            }
        }
        return elegido;
    }

    // -- SONDEO DE SALUD ------------------------------------------
    private void iniciarSondeoSalud() {
        sondeoScheduler = Executors.newScheduledThreadPool(1);
        sondeoScheduler.scheduleAtFixedRate(
                this::ejecutarSondeo,
                0,
                Politicas.BAL_INTERVALO_SONDEO,
                TimeUnit.SECONDS);
    }

    private void ejecutarSondeo() {
        int disponibles = 0;
        for (ServidorBackend b : backends) {
            boolean estabaDisponible = b.isDisponible();
            boolean respondio        = b.sondear();

            if (!estabaDisponible && b.isDisponible()) {
                log("[SONDEO] Backend recuperado: " + b.getId());
            } else if (estabaDisponible && !b.isDisponible()) {
                log("[SONDEO] Backend caido: " + b.getId()
                        + " (activas liberadas: " + b.getConexionesActivas() + ")");
            } else {
                log("[SONDEO] " + b.getId()
                        + " → " + (respondio ? "OK" : "SIN RESPUESTA")
                        + " | activas=" + b.getConexionesActivas());
            }

            actualizarLabelBackend(b);
            if (b.isDisponible()) disponibles++;
        }

        final int disp = disponibles;
        SwingUtilities.invokeLater(() ->
                lblBackends.setText("Backends: " + disp + "/" + backends.size() + " disponibles"));
    }

    private void detenerSondeo() {
        if (sondeoScheduler != null && !sondeoScheduler.isShutdown())
            sondeoScheduler.shutdownNow();
    }

    // -- PROXY BIDIRECCIONAL --------------------------------------
    /**
     * Clase interna que gestiona la comunicacion entre un cliente
     * y su backend asignado mediante dos hilos:
     *   Hilo 1: cliente → backend (reenvio de mensajes del cliente)
     *   Hilo 2: backend → cliente (reenvio de respuestas del servidor)
     *
     * Al terminar cualquiera de los dos hilos, cierra ambos sockets
     * y libera el contador del backend.
     */
    private class ProxyBidireccional extends Thread {

        private final Socket          clienteSocket;
        private final Socket          backendSocket;
        private final ServidorBackend backend;
        private final AtomicBoolean   cerrado = new AtomicBoolean(false);

        ProxyBidireccional(Socket cs, Socket bs, ServidorBackend b) {
            this.clienteSocket = cs;
            this.backendSocket = bs;
            this.backend       = b;
        }

        @Override
        public void run() {
            // Hilo cliente → backend
            Thread cliABack = new Thread(() ->
                    reenviar(clienteSocket, backendSocket, "CLI→BAK"), "cli-bak");

            // Hilo backend → cliente
            Thread backACli = new Thread(() ->
                    reenviar(backendSocket, clienteSocket, "BAK→CLI"), "bak-cli");

            cliABack.start();
            backACli.start();

            // Esperar a que terminen ambos
            try { cliABack.join(); } catch (InterruptedException ignored) {}
            try { backACli.join(); } catch (InterruptedException ignored) {}

            cerrarConexion();
        }

        private void reenviar(Socket origen, Socket destino, String dir) {
            try {
                InputStream  is = origen.getInputStream();
                OutputStream os = destino.getOutputStream();
                byte[] buf = new byte[4096];
                int leidos;
                while ((leidos = is.read(buf)) != -1) {
                    os.write(buf, 0, leidos);
                    os.flush();
                }
            } catch (IOException ex) {
                // Conexion cerrada normalmente o por caida
            } finally {
                // Cerrar el socket de destino para que el otro hilo termine
                try { destino.close(); } catch (IOException ignored) {}
            }
        }

        private void cerrarConexion() {
            if (!cerrado.compareAndSet(false, true)) return;

            try { clienteSocket.close(); } catch (IOException ignored) {}
            try { backendSocket.close(); } catch (IOException ignored) {}

            backend.liberarConexion();
            int activos = clientesActivos.decrementAndGet();
            actualizarLabelBackend(backend);
            actualizarContadorClientes(activos);

            log("[BAL] Conexion cerrada → " + backend.getId()
                    + " (activas en backend: " + backend.getConexionesActivas() + ")");
        }
    }

    // -- ACTUALIZAR UI --------------------------------------------
    private void actualizarEstado(String texto, Color color) {
        SwingUtilities.invokeLater(() -> {
            lblEstado.setText(texto);
            lblEstado.setForeground(color);
        });
    }

    private void actualizarContadorClientes(int n) {
        SwingUtilities.invokeLater(() ->
                lblClientes.setText("Clientes activos: " + n));
    }

    private void actualizarLabelBackend(ServidorBackend b) {
        SwingUtilities.invokeLater(() -> {
            JLabel lbl = labelsBackend.get(b.getId());
            if (lbl == null) return;
            lbl.setText(formatearLabelBackend(b));
            if (b.isDisponible()) {
                lbl.setForeground(new Color(0, 120, 0));
                lbl.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(new Color(0, 150, 0)),
                        BorderFactory.createEmptyBorder(3, 8, 3, 8)));
            } else {
                lbl.setForeground(new Color(180, 0, 0));
                lbl.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(new Color(180, 0, 0)),
                        BorderFactory.createEmptyBorder(3, 8, 3, 8)));
            }
        });
    }

    private String formatearLabelBackend(ServidorBackend b) {
        return b.getId()
                + "  " + (b.isDisponible() ? "[OK]" : "[CAIDO]")
                + "  activas=" + b.getConexionesActivas();
    }

    // -- LOG ------------------------------------------------------
    private void log(String texto) {
        String hora = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        SwingUtilities.invokeLater(() -> {
            logArea.append("[" + hora + "] " + texto + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    // -- MAIN -----------------------------------------------------
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            BalanceadorTCP bal = new BalanceadorTCP();
            bal.setVisible(true);
        });
    }
}