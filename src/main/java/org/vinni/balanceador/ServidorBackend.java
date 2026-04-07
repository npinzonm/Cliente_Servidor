package org.vinni.balanceador;

import org.vinni.config.Politicas;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ServidorBackend.java
 *
 * Representa un servidor backend dentro del pool del balanceador.
 * Mantiene el estado de salud y el conteo de conexiones activas
 * para el algoritmo Least Connections.
 *
 * Estados posibles:
 *   DISPONIBLE  — responde al sondeo, acepta nuevas conexiones
 *   CAIDO       — no responde, excluido del balanceo
 *   RECUPERANDO — respondio N veces pero aun no alcanza BAL_EXITOS_PARA_ALTA
 *
 * Author: Nathalie Pinzon 2026
 */
public class ServidorBackend {

    // -- Identificacion -------------------------------------------
    private final String host;
    private final int    puerto;
    private final String id; // "localhost:12345"

    // -- Estado de salud ------------------------------------------
    private final AtomicBoolean disponible      = new AtomicBoolean(false);
    private final AtomicInteger fallosConsec    = new AtomicInteger(0);
    private final AtomicInteger exitosConsec    = new AtomicInteger(0);

    // -- Least Connections ----------------------------------------
    /** Numero de clientes actualmente enrutados a este servidor. */
    private final AtomicInteger conexionesActivas = new AtomicInteger(0);

    /** Total historico de conexiones atendidas (para el log). */
    private final AtomicInteger conexionesTotales = new AtomicInteger(0);

    public ServidorBackend(String host, int puerto) {
        this.host   = host;
        this.puerto = puerto;
        this.id     = host + ":" + puerto;
    }

    // -- Sondeo de salud ------------------------------------------

    /**
     * Intenta conectar al backend para verificar que esta vivo.
     * Actualiza el estado segun los resultados consecutivos:
     *   - BAL_FALLOS_PARA_CAIDA fallos → marca como caido
     *   - BAL_EXITOS_PARA_ALTA exitos  → marca como disponible
     *
     * @return true si el backend respondio en este sondeo
     */
    public boolean sondear() {
        boolean vivo = intentarConexion();

        if (vivo) {
            fallosConsec.set(0);
            int exitos = exitosConsec.incrementAndGet();
            if (!disponible.get() && exitos >= Politicas.BAL_EXITOS_PARA_ALTA) {
                disponible.set(true);
                exitosConsec.set(0);
            }
        } else {
            exitosConsec.set(0);
            int fallos = fallosConsec.incrementAndGet();
            if (disponible.get() && fallos >= Politicas.BAL_FALLOS_PARA_CAIDA) {
                disponible.set(false);
                fallosConsec.set(0);
            }
        }

        return vivo;
    }

    private boolean intentarConexion() {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, puerto),
                    Politicas.BAL_TIMEOUT_SONDEO);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // -- Contadores de conexiones ---------------------------------

    /** Llamar cuando un cliente es asignado a este servidor. */
    public void registrarConexion() {
        conexionesActivas.incrementAndGet();
        conexionesTotales.incrementAndGet();
    }

    /** Llamar cuando un cliente se desconecta de este servidor. */
    public void liberarConexion() {
        int actual = conexionesActivas.decrementAndGet();
        if (actual < 0) conexionesActivas.set(0); // guardia
    }

    // -- Forzar estado (para el Watchdog) -------------------------

    /** Marcar como disponible sin esperar los sondeos de alta. */
    public void marcarDisponible() {
        disponible.set(true);
        fallosConsec.set(0);
        exitosConsec.set(0);
    }

    /** Marcar como caido inmediatamente. */
    public void marcarCaido() {
        disponible.set(false);
        fallosConsec.set(0);
        exitosConsec.set(0);
    }

    // -- Getters --------------------------------------------------

    public String  getId()                { return id; }
    public String  getHost()              { return host; }
    public int     getPuerto()            { return puerto; }
    public boolean isDisponible()         { return disponible.get(); }
    public int     getConexionesActivas() { return conexionesActivas.get(); }
    public int     getConexionesTotales() { return conexionesTotales.get(); }
    public int     getFallosConsec()      { return fallosConsec.get(); }
    public int     getExitosConsec()      { return exitosConsec.get(); }

    @Override
    public String toString() {
        return id + " ["
                + (disponible.get() ? "OK" : "CAIDO")
                + " | activas=" + conexionesActivas.get()
                + " | totales=" + conexionesTotales.get()
                + "]";
    }
}