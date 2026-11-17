// Proves the four Android signing secrets open the upload keystore, before
// the release build spends twenty minutes finding out.
//
//   java scripts/mobile/CheckKeystore.java <keystore file>
//
// Reads STORE_PASSWORD, KEY_ALIAS and KEY_PASSWORD from the environment: the
// same names app/build.gradle.kts's signingValue() reads, so this checks
// exactly what the build will use. Nothing secret is printed, not even the
// alias; messages say which of the four failed.
//
// Java rather than `keytool -list`: -list proves the store password and the
// alias, but never touches the key password, and for a PKCS12 store keytool
// ignores -keypass altogether ("Different store and key passwords not
// supported"). KeyStore.getKey(alias, keyPassword) is the call the signer
// makes, so a wrong KEY_PASSWORD fails here too. Run as a single source file
// (JEP 330), so there is nothing to compile or commit as a binary.
import java.io.File;
import java.io.IOException;
import java.security.Key;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.UnrecoverableKeyException;

public class CheckKeystore {
    public static void main(String[] args) {
        if (args.length != 1) {
            fail("usage: CheckKeystore <keystore file>", 2);
        }
        File file = new File(args[0]);
        if (!file.isFile() || file.length() == 0) {
            fail("the decoded keystore is missing or empty — is ANDROID_KEYSTORE_BASE64 the base64 of the .jks?", 1);
        }
        String storePassword = System.getenv("STORE_PASSWORD");
        String alias = System.getenv("KEY_ALIAS");
        String keyPassword = System.getenv("KEY_PASSWORD");
        if (isEmpty(storePassword) || isEmpty(alias) || isEmpty(keyPassword)) {
            fail("STORE_PASSWORD, KEY_ALIAS and KEY_PASSWORD must all be set", 1);
        }

        KeyStore store = null;
        try {
            // Detects JKS or PKCS12 from the file itself.
            store = KeyStore.getInstance(file, storePassword.toCharArray());
        } catch (IOException e) {
            if (e.getCause() instanceof UnrecoverableKeyException) {
                fail("ANDROID_STORE_PASSWORD does not open the keystore", 1);
            }
            fail("ANDROID_KEYSTORE_BASE64 does not decode to a readable keystore (" + e.getClass().getSimpleName() + ")", 1);
        } catch (Exception e) {
            fail("ANDROID_KEYSTORE_BASE64 does not decode to a readable keystore (" + e.getClass().getSimpleName() + ")", 1);
        }

        try {
            if (!store.containsAlias(alias)) {
                fail("ANDROID_KEY_ALIAS is not an entry in the keystore", 1);
            }
            if (!store.isKeyEntry(alias)) {
                fail("ANDROID_KEY_ALIAS names a certificate, not a signing key", 1);
            }
            Key key = store.getKey(alias, keyPassword.toCharArray());
            if (!(key instanceof PrivateKey)) {
                fail("ANDROID_KEY_ALIAS does not hold a private key", 1);
            }
        } catch (UnrecoverableKeyException e) {
            fail("ANDROID_KEY_PASSWORD does not unlock the signing key", 1);
        } catch (Exception e) {
            fail("the keystore could not be read (" + e.getClass().getSimpleName() + ")", 1);
        }
        System.out.println("keystore opens: store password, alias and key password all verified");
    }

    private static boolean isEmpty(String s) {
        return s == null || s.isEmpty();
    }

    private static void fail(String message, int status) {
        System.out.println("::error::" + message);
        System.exit(status);
    }
}
