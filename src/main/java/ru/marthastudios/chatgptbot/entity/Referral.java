package ru.marthastudios.chatgptbot.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "referrals")
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@Builder
public class Referral {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "telegram_user_id", nullable = false)
    private Long telegramUserId;
}
