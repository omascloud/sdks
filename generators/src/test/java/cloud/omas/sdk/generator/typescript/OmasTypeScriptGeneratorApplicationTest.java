package cloud.omas.sdk.generator.typescript;

import org.testng.annotations.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;

public class OmasTypeScriptGeneratorApplicationTest {

    @Test
    public void testHashesBundledContractWithoutLocalSchema() throws Exception {
        try (var resource = getClass().getResourceAsStream("/schema/metrics.yaml")) {
            assertNotNull(resource);
            String expected = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(resource.readAllBytes()));
            assertEquals(OmasTypeScriptGeneratorApplication.contractDigest(Path.of("schema", "metrics.yaml")),
                    expected);
        }
    }

    @Test
    public void testHashesLocalContractWhenPresent() throws Exception {
        Path schema = Files.createTempFile("omas-contract-", ".yaml");
        try {
            Files.writeString(schema, "abc");
            assertEquals(OmasTypeScriptGeneratorApplication.contractDigest(schema),
                    "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        } finally {
            Files.delete(schema);
        }
    }
}
