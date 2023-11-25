package ru.marthastudios.chatgptbot.util;

public class TelegramTextFormatterUtil {
    public static String replaceCodeTags(String originalCode) {
        StringBuilder result = new StringBuilder();
        boolean insideCodeBlock = false;

        for (char c : originalCode.toCharArray()) {
            if (c == '`') {
                insideCodeBlock = !insideCodeBlock;
                result.append(insideCodeBlock ? "<pre>" : "</pre>");
            } else if (insideCodeBlock && c == '?') {
                result.append("&#63;");
            } else if (insideCodeBlock && c == '<'){
                result.append("&lt;");
            } else if (insideCodeBlock && c == '>') {
                result.append("&gt;");
            }else if (insideCodeBlock && c == 'p'){
                result.append("&#112;");
            } else {
                result.append(c);
            }
        }

//        for (char c : originalCode.toCharArray()) {
//            if (c == '`') {
//                insideCodeBlock = !insideCodeBlock;
//                result.append(insideCodeBlock ? "<pre>" : "</pre>");
//            } else{
//                result.append(c);
//            }
//        }

        return result.toString();
    }
}
