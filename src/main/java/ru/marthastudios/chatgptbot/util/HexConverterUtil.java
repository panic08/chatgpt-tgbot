package ru.marthastudios.chatgptbot.util;
public class HexConverterUtil {

    public static String toHex(String message) {
        String hex = "";
        for (char ch : message.toCharArray()) {
            int decimal = (int) ch;
            hex += Integer.toHexString(decimal);
        }
        return hex;
    }

    public static String fromHex(String hex) {
        String message = "";
        for (int i = 0; i < hex.length(); i += 2) {
            String part = hex.substring(i, i + 2);
            char ch = (char) Integer.parseInt(part, 16);
            message += ch;
        }
        return message;
    }
}
