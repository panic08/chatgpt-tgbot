package ru.marthastudios.chatgptbot.pojo;

import lombok.*;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class CustomPair<T, E> {
    private T firstValue;
    private E secondValue;
}
