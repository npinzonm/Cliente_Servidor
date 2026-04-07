package org.vinni.config;

/**
 * Politicas.java
 * Author: Nathalie Pinzon 2026
 */
public final class Politicas {

    // Clase utilitaria — no instanciable
    private Politicas() {}

    // =========================================================
    // BALANCEADOR
    // =========================================================

    /** Puerto en el que el balanceador escucha conexiones de clientes. */
    public static final int    BAL_PUERTO_ENTRADA    = 9000;

    /**
     * Puertos de los servidores backend.
     * Agregar o quitar puertos aqui define cuantos servidores
     * participan en el pool. El balanceador gestiona todos.
     */
    public static final int[]  BAL_PUERTOS_BACKEND   = {12345, 12346, 12347};

    /** Host donde corren los servidores backend. */
    public static final String BAL_HOST_BACKEND      = "localhost";

    /**
     * Intervalo en segundos entre cada sondeo de salud a los backends.
     * El balanceador verifica que cada servidor sigue respondiendo.
     */
    public static final int    BAL_INTERVALO_SONDEO  = 5;

    /**
     * Timeout en milisegundos para el sondeo de salud.
     * Si el servidor no responde en este tiempo, se marca como caido.
     */
    public static final int    BAL_TIMEOUT_SONDEO    = 2000;

    /**
     * Numero de fallos consecutivos de sondeo para marcar un backend
     * como no disponible y excluirlo del pool de balanceo.
     */
    public static final int    BAL_FALLOS_PARA_CAIDA = 2;

    /**
     * Numero de sondeos exitosos consecutivos para re-incorporar
     * un backend caido al pool de balanceo.
     */
    public static final int    BAL_EXITOS_PARA_ALTA  = 2;

    /** Maximo de clientes que el balanceador acepta en total. */
    public static final int    BAL_MAX_CLIENTES      = 50;

    /** Timeout en ms para conectar al backend al redirigir un cliente. */
    public static final int    BAL_TIMEOUT_BACKEND   = 3000;

    // =========================================================
    // SERVIDOR BACKEND (PrincipalSrv)
    // =========================================================

    /** Maximo de clientes por instancia de servidor backend. */
    public static final int    SRV_MAX_CLIENTES         = 10;

    /** Segundos entre pings de heartbeat a cada cliente. */
    public static final int    SRV_INTERVALO_HEARTBEAT  = 30;

    /** Segundos esperando PONG antes de desconectar al cliente. */
    public static final int    SRV_TIMEOUT_HEARTBEAT    = 10;

    /** Tamanio maximo de archivo transferible en bytes (10 MB). */
    public static final long   SRV_MAX_TAMANIO_ARCHIVO  = 10 * 1024 * 1024;

    /** Extensiones de archivo bloqueadas por seguridad. */
    public static final String[] SRV_EXT_BLOQUEADAS     =
            {".exe", ".bat", ".sh", ".cmd", ".msi"};

    /** Regex de validacion para nombres de usuario. */
    public static final String SRV_REGEX_NOMBRE          = "^[a-zA-Z0-9_]{2,20}$";

    /** Palabras reservadas que no pueden usarse como nombre de usuario. */
    public static final String[] SRV_PALABRAS_RESERVADAS =
            {"SERVIDOR", "TODOS", "*", "BALANCEADOR"};

    // =========================================================
    // CLIENTE (PrincipalCli)
    // =========================================================

    /**
     * Host al que el cliente se conecta.
     * Con balanceador: apunta al balanceador, no al servidor directamente.
     */
    public static final String CLI_HOST             = "localhost";

    /**
     * Puerto al que el cliente se conecta.
     * Con balanceador activo: usar BAL_PUERTO_ENTRADA (9000).
     * Sin balanceador (conexion directa): usar primer puerto del backend.
     */
    public static final int    CLI_PUERTO           = BAL_PUERTO_ENTRADA;

    /** Puerto del canal binario para transferencia de archivos. */
    public static final int    CLI_PUERTO_ARCHIVOS  = 9001;

    /** Numero maximo de reintentos de conexion con backoff exponencial. */
    public static final int    CLI_MAX_REINTENTOS   = 5;

    /** Espera base en segundos para el backoff (se duplica en cada fallo). */
    public static final int    CLI_DELAY_BASE       = 2;

    /** Timeout en ms por cada intento de conexion. */
    public static final int    CLI_TIMEOUT_CONEXION = 5000;

    // =========================================================
    // WATCHDOG
    // =========================================================

    /** Puertos que el Watchdog monitorea (mismo arreglo que los backends). */
    public static final int[]  WD_PUERTOS           = BAL_PUERTOS_BACKEND;

    /** Segundos entre sondeos del Watchdog. */
    public static final int    WD_INTERVALO_SONDEO  = 10;

    /** Fallos consecutivos para confirmar caida de un servidor. */
    public static final int    WD_FALLOS_REINICIO   = 2;

    /** Segundos de espera antes de relanzar un servidor caido. */
    public static final int    WD_DELAY_REINICIO    = 5;

    /** Maximo de reinicios automaticos por servidor. */
    public static final int    WD_MAX_REINICIOS     = 3;

    /** Timeout en ms para el sondeo del Watchdog. */
    public static final int    WD_TIMEOUT_SONDEO    = 3000;

    // =========================================================
    // PERSISTENCIA (rutas en disco)
    // =========================================================

    private static final String HOME = System.getProperty("user.home");
    private static final String SEP  = java.io.File.separator;

    public static final String DIR_SERVIDOR_BASE  = HOME + SEP + "ServidorTCP";
    public static final String DIR_SERVIDOR_LOGS  = DIR_SERVIDOR_BASE + SEP + "logs";
    public static final String DIR_SERVIDOR_ESTADO= DIR_SERVIDOR_BASE + SEP + "estado";
    public static final String DIR_SERVIDOR_ARCH  = DIR_SERVIDOR_BASE + SEP + "archivos";

    public static final String ARCHIVO_COLA_SRV   = DIR_SERVIDOR_ESTADO + SEP + "cola_mensajes.txt";
    public static final String ARCHIVO_CLIENTES   = DIR_SERVIDOR_ESTADO + SEP + "clientes.txt";
    public static final String FLAG_APAGADO_MANUAL= DIR_SERVIDOR_ESTADO + SEP + "apagado_manual.flag";

    public static final String DIR_CLIENTE_BASE   = HOME + SEP + "ClienteTCP";
    public static final String ARCHIVO_COLA_CLI   = "cola_mensajes.txt";

    // =========================================================
    // CLASES DEL SISTEMA (para ProcessBuilder del Watchdog)
    // =========================================================

    public static final String CLASE_SERVIDOR     = "org.vinni.servidor.gui.PrincipalSrv";
    public static final String CLASE_BALANCEADOR  = "org.vinni.balanceador.BalanceadorTCP";
}