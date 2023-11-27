package ru.marthastudios.chatgptbot.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "deposits")
@AllArgsConstructor
@NoArgsConstructor
@Setter
@Getter
@Builder
public class Deposit {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "telegram_user_id", nullable = false)
    private Long telegramUserId;
    @Column(name = "amount", nullable = false)
    private Double amount;
    @Column(name = "timestamp", nullable = false)
    private Long timestamp;
}
