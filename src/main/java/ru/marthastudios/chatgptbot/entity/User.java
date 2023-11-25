package ru.marthastudios.chatgptbot.entity;

import jakarta.persistence.*;
import lombok.*;
import ru.marthastudios.chatgptbot.enums.UserRole;

@Entity
@Table(name = "users")
@AllArgsConstructor
@NoArgsConstructor
@Setter
@Getter
@Builder
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "telegram_user_id", nullable = false)
    private Long telegramUserId;
    @Column(name = "telegram_chat_id", nullable = false)
    private Long telegramChatId;
    @Column(name = "account_non_locked", nullable = false)
    private Boolean isAccountNonLocked;
    @Column(name = "role", nullable = false)
    @Enumerated(value = EnumType.STRING)
    private UserRole role;
    @Column(nullable = false)
    private Long timestamp;
    @OneToOne(cascade = CascadeType.ALL, mappedBy = "user", fetch = FetchType.EAGER)
    private UserData userData;
}
