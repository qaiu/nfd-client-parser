package cn.qaiu.nfd.share;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.json.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AuthStoreTest {

    @TempDir
    Path dir;

    @Test
    void storesOnlyKnownFieldsAndKeepsBlankSecrets() throws Exception {
        Path file = dir.resolve("auths.json");
        AuthStore store = AuthStore.load(file);
        store.update("ye", Map.of("token", "Bearer abc", "username", "me", "bogus", "x"));
        store.update("ye", Map.of("token", "", "username", ""));

        AuthStore reloaded = AuthStore.load(file);
        assertEquals(Map.of("token", "Bearer abc"), reloaded.get("ye"));
        assertFalse(Files.readString(file).contains("bogus"));
        assertEquals(Map.of(), reloaded.get("qk"));
        assertThrows(IllegalArgumentException.class, () -> store.update("nope", Map.of()));
    }

    @Test
    void describeHidesSecretsButShowsAccounts() throws Exception {
        AuthStore store = AuthStore.load(dir.resolve("auths.json"));
        String account = "account-" + dir.getFileName();
        String secret = UUID.randomUUID().toString();
        store.update("fj", Map.of("username", account, "password", secret));
        String described = store.describe().encode();
        assertFalse(described.contains(secret));
        assertTrue(described.contains(account));
        assertTrue(store.configured("fj"));
        store.clear("fj");
        assertFalse(store.configured("fj"));
    }

    @Test
    void folderTypeSharesTheFileTypeLogin() throws Exception {
        AuthStore store = AuthStore.load(dir.resolve("auths.json"));
        store.update("ct", Map.of("refresh_token", "rt"));
        assertEquals("rt", store.get("ctd").get("refresh_token"));
    }

    @Test
    void corruptFileStartsEmpty() throws Exception {
        Path file = Files.writeString(dir.resolve("auths.json"), "{not json");
        assertEquals(Map.of(), AuthStore.load(file).get("qk"));
        assertTrue(new JsonObject(AuthStore.load(file).describe().encode()).containsKey("providers"));
    }
}
