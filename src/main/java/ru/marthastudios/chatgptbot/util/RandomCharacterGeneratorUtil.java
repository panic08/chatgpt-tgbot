package ru.marthastudios.chatgptbot.util;

import java.util.Random;

public class RandomCharacterGeneratorUtil {
    public static String generateRandomCharacters(int length) {
        String characters = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz123456789$";
        StringBuilder result = new StringBuilder(length);
        Random rnd = new Random();

        for (int i = 0; i < length; i++) {
            int index = rnd.nextInt(characters.length());
            result.append(characters.charAt(index));
        }

        return result.toString();
    }
}
