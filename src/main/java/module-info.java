/**
 * RMCache — High-performance off-heap cache for billion-scale JVM applications.
 *
 * <h2>Exported API packages</h2>
 * <ul>
 *   <li>{@code com.codeabbot.rmcache} — main cache interface and builder</li>
 *   <li>{@code com.codeabbot.rmcache.eviction} — eviction policies and listeners</li>
 *   <li>{@code com.codeabbot.rmcache.serializer} — key/value serializer contracts</li>
 * </ul>
 *
 * <h2>Internal packages (not exported)</h2>
 * {@code index}, {@code memory}, and {@code util} are internal implementation
 * packages and are intentionally not exported.
 *
 * <h2>Test reflection note</h2>
 * Some tests (e.g., {@code GhostCacheAutoModeTest}) use {@code setAccessible(true)}
 * on private fields of {@code OffHeapCacheImpl}. This works because Gradle compiles
 * and runs tests on the <em>classpath</em> (unnamed module), not the module path.
 * In classpath mode the JVM does not enforce strong encapsulation between named and
 * unnamed modules, so no {@code opens} directive is required. If test compilation is
 * ever switched to module-path mode, an {@code opens com.codeabbot.rmcache} directive
 * (or a package-visible test-hook method) will be needed.
 */
module com.codeabbot.rmcache {
    requires org.slf4j;

    exports com.codeabbot.rmcache;
    exports com.codeabbot.rmcache.eviction;
    exports com.codeabbot.rmcache.serializer;
}
