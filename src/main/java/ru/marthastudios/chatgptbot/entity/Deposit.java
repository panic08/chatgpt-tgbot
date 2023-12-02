package ru.marthastudios.chatgptbot.entity;

import jakarta.persistence.*;
import lombok.*;
import ru.marthastudios.chatgptbot.enums.DepositCurrency;

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
    @Column(name = "currency", nullable = false)
    @Enumerated(value = EnumType.STRING)
    private DepositCurrency currency;
    @Column(name = "timestamp", nullable = false)
    private Long timestamp;
}
