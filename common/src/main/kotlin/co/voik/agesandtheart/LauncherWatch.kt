package co.voik.agesandtheart

/**
 * A server started by one of our tools, halting itself when the tool that started it is gone.
 *
 * **This exists for the one case nothing else can cover.** A launcher kills what it started from a
 * shutdown hook, which answers an exception, a Ctrl-C and a `SIGTERM`. Nothing in that JVM runs when it is
 * `SIGKILL`ed — and a Gradle run cancelled hard, or an editor killed from another terminal, is exactly
 * that. The server it left behind holds a world lock, a port and a core until somebody notices it, which
 * in practice means hours.
 *
 * So the answer has to come from this end: the launcher tells the server its own process id
 * (`LaunchSpec.LAUNCHED_BY`), and the server watches for it to disappear.
 *
 * **A player never runs this.** The property is set only by `LaunchSpec.start`, which is preview and test
 * code and ships in no jar; without it [attach] returns having done nothing at all. That is also why the
 * check is a property rather than anything cleverer — the gate has to be something a real launch cannot
 * accidentally have.
 */
object LauncherWatch {

    private const val LAUNCHED_BY = "agesandtheart.launchedBy"

    /** Often enough that a stray is measured in seconds, rarely enough to cost nothing. */
    private const val LOOK_EVERY_MILLIS = 2_000L

    /**
     * Starts watching, if this server was launched by a tool of ours.
     *
     * **`System.exit` rather than anything gentler**, and deliberately: it runs Minecraft's own shutdown
     * hook, which stops the server and saves it the way a console `stop` would. Halting the server object
     * directly would need to reach it from a thread that has no business holding it, to do the same thing
     * less reliably.
     */
    fun attach() {
        val launcher = System.getProperty(LAUNCHED_BY)?.toLongOrNull() ?: return
        val watch = Thread {
            while (ProcessHandle.of(launcher).map(ProcessHandle::isAlive).orElse(false)) {
                Thread.sleep(LOOK_EVERY_MILLIS)
            }
            Constants.LOG.warn("The tool that launched this server (pid {}) is gone; stopping.", launcher)
            System.exit(0)
        }
        watch.isDaemon = true
        watch.name = "agesandtheart-launcher-watch"
        watch.start()
        Constants.LOG.info("Launched by pid {}; this server will stop when that does.", launcher)
    }
}
