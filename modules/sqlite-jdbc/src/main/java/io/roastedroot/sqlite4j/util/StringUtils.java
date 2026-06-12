package io.roastedroot.sqlite4j.util;

import java.util.List;
import org.jspecify.annotations.NonNull;

public class StringUtils {
    public static String join(@NonNull List<String> list, @NonNull String separator) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (String item : list) {
            if (first) first = false;
            else sb.append(separator);

            sb.append(item);
        }
        return sb.toString();
    }
}
