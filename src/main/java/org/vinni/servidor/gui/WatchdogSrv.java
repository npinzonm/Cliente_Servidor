package org.vinni.servidor.gui;

import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.Socket;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * WatchdogSrv — Proceso externo independiente que monitorea el servidor TCP.
 * Author: Vinni 2024 | Nathalie Pinzon 2026
 */
public class WatchdogSrv extends JFrame {

    // ── Configuración ─────────────────────────────────────────
    private static final String HOST                 = "localhost";
    private static final int    PUERTO_SERVIDOR      = 12345;
    private static final int    INTERVALO_SONDEO     = 10;
    private static final int    FALLOS_PARA_REINICIO = 2;
    private static final int    DELAY_REINICIO       = 5;
    private static final int    MAX_REINICIOS        = 3;
    private static final int    TIMEOUT_SONDEO       = 3000;

    private static final String CLASE_SERVIDOR = "org.vinni.servidor.gui.PrincipalSrv";

    /**
     * [FIX] Ruta del flag que distingue apagado manual (Esc 2) de caída
     * inesperada (Esc 3). Debe coincidir con PrincipalSrv.FLAG_APAGADO_MANUAL.
     */
    private static final String FLAG_APAGADO_MANUAL =
            System.getProperty("user.home") + File.separator
                    + "ServidorTCP" + File.separator
                    + "estado"      + File.separator
                    + "apagado_manual.flag";

    // ── Estado ────────────────────────────────────────────────
    private final AtomicBoolean      monitoreando = new AtomicBoolean(false);
    private final AtomicInteger      fallosConsec = new AtomicInteger(0);
    private final AtomicInteger      reinicios    = new AtomicInteger(0);
    private ScheduledExecutorService scheduler;

    // ── Colores ───────────────────────────────────────────────
    private static final Color COLOR_ACTIVO    = new Color(0, 130, 0);
    private static final Color COLOR_CAIDO     = new Color(180, 0, 0);
    private static final Color COLOR_WATCHDOG  = new Color(80, 0, 140);
    private static final Color COLOR_ESPERA    = new Color(160, 90, 0);
    private static final Color COLOR_GRIS      = new Color(120, 120, 120);
    private static final Color COLOR_FONDO_LOG = new Color(248, 245, 255);

    // ── GUI ───────────────────────────────────────────────────
    private JTextArea  logArea;
    private JLabel     lblEstadoWatchdog;
    private JLabel     lblEstadoServidor;
    private JLabel     lblReinicios;
    private JLabel     lblFallos;
    private JButton    btnIniciar;
    private JButton    btnDetener;
    private JButton    btnLanzarServidor;

    public WatchdogSrv() { initComponents(); }

    // ── INIT GUI ─────────────────────────────────────────────
    private void initComponents() {
        setTitle("WatchdogSrv  —  Monitor de Servidor TCP");
        setSize(660, 580);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout(0, 0));

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                detenerMonitoreo();
                System.exit(0);
            }
        });

        add(construirPanelNorte(), BorderLayout.NORTH);
        add(construirPanelLog(),   BorderLayout.CENTER);
    }

    // ── PANEL NORTE ───────────────────────────────────────────
    private JPanel construirPanelNorte() {
        JPanel panel = new JPanel(new BorderLayout(0, 6));
        panel.setBorder(BorderFactory.createEmptyBorder(14, 18, 8, 18));
        panel.setBackground(new Color(250, 248, 255));

        panel.add(construirFilaTitulo(),  BorderLayout.NORTH);
        panel.add(construirFilaEstados(), BorderLayout.CENTER);
        panel.add(construirFilaConfig(),  BorderLayout.SOUTH);

        return panel;
    }

    private JPanel construirFilaTitulo() {
        JPanel fila = new JPanel(new BorderLayout(8, 0));
        fila.setOpaque(false);

        JLabel titulo = new JLabel("WATCHDOG  —  Monitor Externo");
        titulo.setFont(new Font("Dialog", Font.BOLD, 15));
        titulo.setForeground(COLOR_WATCHDOG);

        btnLanzarServidor = crearBoton("LANZAR SERVIDOR", new Color(0, 110, 170));
        btnLanzarServidor.addActionListener(e -> lanzarServidorManual());

        btnIniciar = crearBoton("INICIAR WATCHDOG", COLOR_WATCHDOG);
        btnIniciar.addActionListener(e -> iniciarMonitoreo());

        btnDetener = crearBoton("DETENER", COLOR_CAIDO);
        btnDetener.setEnabled(false);
        btnDetener.setBackground(COLOR_GRIS);
        btnDetener.addActionListener(e -> detenerMonitoreo());

        JPanel panelBotones = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        panelBotones.setOpaque(false);
        panelBotones.add(btnLanzarServidor);
        panelBotones.add(btnIniciar);
        panelBotones.add(btnDetener);

        fila.add(titulo,       BorderLayout.WEST);
        fila.add(panelBotones, BorderLayout.EAST);
        return fila;
    }

    private JPanel construirFilaEstados() {
        JPanel fila = new JPanel(new GridLayout(1, 4, 10, 0));
        fila.setOpaque(false);
        fila.setBorder(BorderFactory.createEmptyBorder(6, 0, 4, 0));

        lblEstadoWatchdog = new JLabel("Watchdog: Detenido");
        lblEstadoWatchdog.setFont(new Font("Dialog", Font.BOLD, 12));
        lblEstadoWatchdog.setForeground(COLOR_GRIS);

        lblEstadoServidor = new JLabel("Servidor: Desconocido");
        lblEstadoServidor.setFont(new Font("Dialog", Font.BOLD, 12));
        lblEstadoServidor.setForeground(COLOR_GRIS);

        lblReinicios = new JLabel("Reinicios: 0 / " + MAX_REINICIOS);
        lblReinicios.setFont(new Font("Dialog", Font.PLAIN, 12));
        lblReinicios.setForeground(COLOR_ESPERA);

        lblFallos = new JLabel("Fallos consecutivos: 0 / " + FALLOS_PARA_REINICIO);
        lblFallos.setFont(new Font("Dialog", Font.PLAIN, 12));
        lblFallos.setForeground(COLOR_GRIS);

        fila.add(lblEstadoWatchdog);
        fila.add(lblEstadoServidor);
        fila.add(lblReinicios);
        fila.add(lblFallos);
        return fila;
    }

    private JPanel construirFilaConfig() {
        JPanel fila = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        fila.setOpaque(false);

        JLabel config = new JLabel(
                "<html><i>Puerto: " + PUERTO_SERVIDOR +
                        "  |  Sondeo: cada " + INTERVALO_SONDEO + "s" +
                        "  |  Fallos para reiniciar: " + FALLOS_PARA_REINICIO +
                        "  |  Delay reinicio: " + DELAY_REINICIO + "s" +
                        "  |  Max reinicios: " + MAX_REINICIOS +
                        "  |  <b>Esc2=flag manual / Esc3=caída inesperada</b></i></html>"
        );
        config.setFont(new Font("Dialog", Font.PLAIN, 11));
        config.setForeground(new Color(130, 130, 130));

        fila.add(config);
        return fila;
    }

    private JPanel construirPanelLog() {
        logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
        logArea.setBackground(COLOR_FONDO_LOG);
        logArea.setMargin(new Insets(6, 8, 6, 8));

        JScrollPane scroll = new JScrollPane(logArea);
        scroll.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(200, 185, 230)),
                "  Log del Watchdog  ",
                TitledBorder.LEFT, TitledBorder.TOP,
                new Font("Dialog", Font.PLAIN, 11),
                COLOR_WATCHDOG
        ));

        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(0, 18, 14, 18));
        panel.add(scroll);
        return panel;
    }

    private JButton crearBoton(String texto, Color fondo) {
        JButton btn = new JButton(texto);
        btn.setBackground(fondo);
        btn.setForeground(Color.WHITE);
        btn.setFont(new Font("Dialog", Font.BOLD, 11));
        btn.setOpaque(true);
        btn.setBorderPainted(false);
        btn.setFocusPainted(false);
        return btn;
    }

    // ── INICIAR MONITOREO ─────────────────────────────────────
    private void iniciarMonitoreo() {
        if (monitoreando.get()) return;
        monitoreando.set(true);
        fallosConsec.set(0);
        // [FIX] Resetear contadores para permitir un nuevo ciclo completo
        reinicios.set(0);
        actualizarReinicios();

        SwingUtilities.invokeLater(() -> {
            btnIniciar.setEnabled(false);
            btnIniciar.setBackground(COLOR_GRIS);
            btnDetener.setEnabled(true);
            btnDetener.setBackground(COLOR_CAIDO);
        });

        actualizarEstadoWatchdog("Monitoreando", COLOR_WATCHDOG);

        log("-----------------------------------------------------");
        log("[WATCHDOG] Monitoreo iniciado");
        log("[CONFIG]   Puerto          : " + PUERTO_SERVIDOR);
        log("[CONFIG]   Sondeo          : cada " + INTERVALO_SONDEO + "s");
        log("[CONFIG]   Fallos          : " + FALLOS_PARA_REINICIO + " para reiniciar");
        log("[CONFIG]   Delay reinicio  : " + DELAY_REINICIO + "s");
        log("[CONFIG]   Max reinicios   : " + MAX_REINICIOS);
        log("[CONFIG]   Flag manual     : " + FLAG_APAGADO_MANUAL);
        log("-----------------------------------------------------");

        scheduler = Executors.newScheduledThreadPool(1);
        scheduler.scheduleAtFixedRate(this::sondear, 0, INTERVALO_SONDEO, TimeUnit.SECONDS);
    }

    // ── SONDEO ────────────────────────────────────────────────
    private void sondear() {
        if (!monitoreando.get()) return;

        boolean vivo = intentarConexion();

        if (vivo) {
            fallosConsec.set(0);
            actualizarEstadoServidor("ACTIVO", COLOR_ACTIVO);
            actualizarFallos(0);
            log("[SONDEO]  Puerto " + PUERTO_SERVIDOR + " responde — OK");
        } else {
            int fallos = fallosConsec.incrementAndGet();
            actualizarEstadoServidor("CAIDO  (" + fallos + "/" + FALLOS_PARA_REINICIO + ")", COLOR_CAIDO);
            actualizarFallos(fallos);
            log("[SONDEO]  Sin respuesta en puerto " + PUERTO_SERVIDOR
                    + "  —  fallo " + fallos + "/" + FALLOS_PARA_REINICIO);

            if (fallos >= FALLOS_PARA_REINICIO) {
                fallosConsec.set(0);
                actualizarFallos(0);
                manejarCaida();
            }
        }
    }

    // ── MANEJAR CAIDA ─────────────────────────────────────────
    /**
     * [FIX] Diferencia entre Escenario 2 y Escenario 3:
     *
     * Escenario 2 (apagado manual — botón APAGAR):
     *   PrincipalSrv escribió apagado_manual.flag ANTES de cerrar.
     *   → El Watchdog borra el flag y detiene el monitoreo SIN reiniciar.
     *
     * Escenario 3 (caída inesperada — botón X / kill / excepción):
     *   PrincipalSrv NO escribió el flag.
     *   → El Watchdog reinicia el servidor automáticamente.
     */
    private void manejarCaida() {
        // ── ESCENARIO 2: ¿fue un apagado intencional? ─────────
        File flag = new File(FLAG_APAGADO_MANUAL);
        if (flag.exists()) {
            flag.delete(); // consumir el flag
            log("-----------------------------------------------------");
            log("[WATCHDOG] Flag de apagado manual detectado.");
            log("[ESCENARIO 2] Apagado intencional — NO se reinicia el servidor.");
            log("[WATCHDOG] Monitoreo detenido. Usa LANZAR SERVIDOR cuando lo necesites.");
            log("-----------------------------------------------------");
            actualizarEstadoWatchdog("Apagado manual detectado", COLOR_ESPERA);
            actualizarEstadoServidor("APAGADO (manual)", COLOR_GRIS);
            detenerMonitoreo();
            return;
        }

        // ── ESCENARIO 3: caída inesperada ─────────────────────
        if (reinicios.get() >= MAX_REINICIOS) {
            log("-----------------------------------------------------");
            log("[WATCHDOG] Limite de reinicios alcanzado ("
                    + MAX_REINICIOS + "/" + MAX_REINICIOS + ")");
            log("[WATCHDOG] Intervencion manual requerida.");
            log("[WATCHDOG] Usa el boton LANZAR SERVIDOR para continuar.");
            log("-----------------------------------------------------");
            actualizarEstadoWatchdog("Limite alcanzado", COLOR_CAIDO);
            detenerMonitoreo();
            return;
        }

        reinicios.incrementAndGet();
        actualizarReinicios();

        log("-----------------------------------------------------");
        log("[WATCHDOG] Caida inesperada confirmada  —  Reinicio "
                + reinicios.get() + "/" + MAX_REINICIOS);
        log("[ESCENARIO 3] Relanzando servidor en " + DELAY_REINICIO + "s...");
        actualizarEstadoWatchdog("Reiniciando servidor", COLOR_ESPERA);

        new Thread(() -> {
            for (int s = DELAY_REINICIO; s >= 1; s--) {
                log("[WATCHDOG] Relanzando en " + s + "s...");
                try { Thread.sleep(1000); }
                catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            lanzarProceso();
        }).start();
    }

    // ── LANZAR PROCESO ────────────────────────────────────────
    /**
     * [FIX] Usa el classpath real del proceso actual para garantizar que
     * el servidor hijo reciba exactamente el mismo entorno.
     * Si falla (entorno IDE con classpath dinámico), loguea el error en
     * lugar de fallar silenciosamente y habilita el botón manual.
     */
    private void lanzarProceso() {
        try {
            String javaExe  = ProcessHandle.current()
                    .info()
                    .command()
                    .orElse("java");
            String classpath = System.getProperty("java.class.path");

            if (classpath == null || classpath.isBlank()) {
                log("[ERROR]   java.class.path vacío — no se puede relanzar automáticamente.");
                log("[INFO]    Usa el botón LANZAR SERVIDOR para iniciarlo manualmente.");
                actualizarEstadoWatchdog("Error de classpath — acción manual", COLOR_CAIDO);
                SwingUtilities.invokeLater(() -> btnLanzarServidor.setEnabled(true));
                return;
            }

            ProcessBuilder pb = new ProcessBuilder(javaExe, "-cp", classpath, CLASE_SERVIDOR);
            pb.redirectErrorStream(true);
            pb.start();

            log("[WATCHDOG] Proceso lanzado: " + CLASE_SERVIDOR);
            log("[WATCHDOG] Esperando que el servidor levante (~3s)...");
            actualizarEstadoWatchdog("Monitoreando", COLOR_WATCHDOG);

            Thread.sleep(3000);
            if (intentarConexion()) {
                log("[WATCHDOG] Servidor activo tras reinicio — OK");
                actualizarEstadoServidor("ACTIVO", COLOR_ACTIVO);
            } else {
                log("[WATCHDOG] Servidor aún no responde — el sondeo continuará verificando.");
            }

        } catch (IOException ex) {
            log("[ERROR]   No se pudo lanzar el proceso: " + ex.getMessage());
            log("[INFO]    Usa el botón LANZAR SERVIDOR para iniciarlo manualmente.");
            actualizarEstadoWatchdog("Error al lanzar — acción manual", COLOR_CAIDO);
            SwingUtilities.invokeLater(() -> btnLanzarServidor.setEnabled(true));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    // ── LANZAR MANUAL ─────────────────────────────────────────
    private void lanzarServidorManual() {
        log("-----------------------------------------------------");
        log("[WATCHDOG] Lanzando servidor manualmente...");
        // Borrar flag si existe (el operador lo relanza a propósito)
        File flag = new File(FLAG_APAGADO_MANUAL);
        if (flag.exists()) {
            flag.delete();
            log("[INFO]    Flag de apagado manual eliminado.");
        }
        reinicios.set(0);
        actualizarReinicios();
        lanzarProceso();
    }

    // ── DETENER MONITOREO ─────────────────────────────────────
    /**
     * [FIX] Idempotente: verifica isShutdown() antes de llamar shutdownNow()
     * para evitar IllegalStateException si se llama varias veces.
     */
    private void detenerMonitoreo() {
        monitoreando.set(false);
        if (scheduler != null && !scheduler.isShutdown())
            scheduler.shutdownNow();

        actualizarEstadoWatchdog("Detenido", COLOR_GRIS);
        SwingUtilities.invokeLater(() -> {
            btnIniciar.setEnabled(true);
            btnIniciar.setBackground(COLOR_WATCHDOG);
            btnDetener.setEnabled(false);
            btnDetener.setBackground(COLOR_GRIS);
        });
        log("[WATCHDOG] Monitoreo detenido.");
    }

    // ── SONDEO DE CONEXION ────────────────────────────────────
    private boolean intentarConexion() {
        try (Socket s = new Socket()) {
            s.connect(new java.net.InetSocketAddress(HOST, PUERTO_SERVIDOR), TIMEOUT_SONDEO);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ── ACTUALIZAR LABELS ─────────────────────────────────────
    private void actualizarEstadoWatchdog(String texto, Color color) {
        SwingUtilities.invokeLater(() -> {
            lblEstadoWatchdog.setText("Watchdog: " + texto);
            lblEstadoWatchdog.setForeground(color);
        });
    }

    private void actualizarEstadoServidor(String texto, Color color) {
        SwingUtilities.invokeLater(() -> {
            lblEstadoServidor.setText("Servidor: " + texto);
            lblEstadoServidor.setForeground(color);
        });
    }

    private void actualizarReinicios() {
        SwingUtilities.invokeLater(() ->
                lblReinicios.setText("Reinicios: " + reinicios.get() + " / " + MAX_REINICIOS));
    }

    private void actualizarFallos(int fallos) {
        SwingUtilities.invokeLater(() -> {
            lblFallos.setText("Fallos consecutivos: " + fallos + " / " + FALLOS_PARA_REINICIO);
            lblFallos.setForeground(fallos > 0 ? COLOR_CAIDO : COLOR_GRIS);
        });
    }

    // ── LOG ───────────────────────────────────────────────────
    private void log(String texto) {
        String hora = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        SwingUtilities.invokeLater(() -> {
            logArea.append("[" + hora + "]  " + texto + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new WatchdogSrv().setVisible(true));
    }
}