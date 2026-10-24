package id.saryu.saryuguard.module;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ModuleLifecycleTest {

    static class DummyModule implements SaryuGuardModule {
        private final String id;
        private boolean enabled = false;
        private ModuleStatus status = ModuleStatus.DISABLED;
        private String statusMessage = "Module idle";

        public DummyModule(String id) {
            this.id = id;
        }

        @Override public @NotNull String id() { return id; }
        @Override public @NotNull String displayName() { return "Dummy " + id; }

        @Override
        public void enable() {
            this.enabled = true;
            this.status = ModuleStatus.ACTIVE;
            this.statusMessage = "Operational";
        }

        @Override
        public void disable() {
            this.enabled = false;
            this.status = ModuleStatus.DISABLED;
            this.statusMessage = "Turned off";
        }

        public void setDegraded(String reason) {
            this.status = ModuleStatus.DEGRADED;
            this.statusMessage = reason;
        }

        public void setError(String error) {
            this.status = ModuleStatus.ERROR;
            this.statusMessage = error;
        }

        @Override public boolean isEnabled() { return enabled; }
        @Override public @NotNull ModuleStatus getStatus() { return status; }
        @Override public @NotNull String getStatusMessage() { return statusMessage; }
    }

    @Test
    @DisplayName("Module lifecycle transitions: DISABLED -> ACTIVE -> DEGRADED -> ERROR -> DISABLED")
    void testLifecycleTransitions() {
        DummyModule module = new DummyModule("test-module");

        // Initial state
        assertFalse(module.isEnabled());
        assertEquals(ModuleStatus.DISABLED, module.getStatus());

        // Enable
        module.enable();
        assertTrue(module.isEnabled());
        assertEquals(ModuleStatus.ACTIVE, module.getStatus());
        assertEquals("Operational", module.getStatusMessage());

        // Degrade
        module.setDegraded("Database unreachable, using cache");
        assertTrue(module.isEnabled());
        assertEquals(ModuleStatus.DEGRADED, module.getStatus());
        assertEquals("Database unreachable, using cache", module.getStatusMessage());

        // Error
        module.setError("Critical internal failure");
        assertEquals(ModuleStatus.ERROR, module.getStatus());

        // Disable
        module.disable();
        assertFalse(module.isEnabled());
        assertEquals(ModuleStatus.DISABLED, module.getStatus());
    }

    @Test
    @DisplayName("Verify real status labels and formatting")
    void testRealStatusLabels() {
        assertEquals("ACTIVE", ModuleStatus.ACTIVE.label());
        assertEquals("DEGRADED", ModuleStatus.DEGRADED.label());
        assertEquals("DISABLED", ModuleStatus.DISABLED.label());
        assertEquals("ERROR", ModuleStatus.ERROR.label());

        assertTrue(ModuleStatus.ACTIVE.formatted().contains("ACTIVE"));
        assertTrue(ModuleStatus.DEGRADED.formatted().contains("DEGRADED"));
        assertTrue(ModuleStatus.DISABLED.formatted().contains("DISABLED"));
        assertTrue(ModuleStatus.ERROR.formatted().contains("ERROR"));
    }
}
