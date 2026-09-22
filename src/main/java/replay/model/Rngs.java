package replay.model;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Base64;
import java.util.Random;

/** 随机数发生器状态的可序列化快照，保证检查点/分叉/落盘后随机序列可精确续放。 */
public final class Rngs {
    private Rngs() {}

    public static String toBase64(Random r) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(bos)) {
            oos.writeObject(r);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return Base64.getEncoder().encodeToString(bos.toByteArray());
    }

    public static Random fromBase64(String s) {
        try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(s)))) {
            return (Random) ois.readObject();
        } catch (IOException | ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }
}
