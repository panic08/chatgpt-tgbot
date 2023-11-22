package ru.marthastudios.chatgptbot.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "users_data")
@AllArgsConstructor
@NoArgsConstructor
@Setter
@Getter
@Builder
public class UserData {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "available_requests", nullable = false)
    private Integer availableRequests;
    @Column(name = "invited", nullable = false)
    private Integer invited;
    @OneToOne
    @JoinColumn(name = "id")
    private User user;
    @OneToOne(cascade = CascadeType.ALL, mappedBy = "userData", fetch = FetchType.EAGER)
    private Subscription subscription;
}
