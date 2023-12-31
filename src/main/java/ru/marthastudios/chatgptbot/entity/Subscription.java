package ru.marthastudios.chatgptbot.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "users_subscriptions")
@AllArgsConstructor
@NoArgsConstructor
@Setter
@Getter
@Builder
public class Subscription {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private Long expiration;
    @OneToOne
    @JoinColumn(name = "user_data_id")
    private UserData userData;
}
