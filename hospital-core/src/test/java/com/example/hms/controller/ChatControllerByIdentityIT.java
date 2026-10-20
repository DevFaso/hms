package com.example.hms.controller;

import com.example.hms.BaseIT;
import com.example.hms.model.ChatMessage;
import com.example.hms.model.User;
import com.example.hms.repository.ChatMessageRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.security.CustomUserDetails;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * QA D343: the by-sender/by-recipient lookups answered 500 because the LAZY
 * sender and recipient were mapped after the repository call's session closed
 * (open-in-view is off). A real persisted message through the real controller,
 * service, mapper and Postgres: a participant gets 200 with both parties
 * populated, anyone else gets 404.
 */
@AutoConfigureMockMvc(addFilters = false)
class ChatControllerByIdentityIT extends BaseIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private ChatMessageRepository chatMessageRepository;

    private User sender;
    private User recipient;
    private User outsider;
    private ChatMessage message;

    @BeforeEach
    void setUp() {
        sender = userRepository.save(user("d343s"));
        recipient = userRepository.save(user("d343r"));
        outsider = userRepository.save(user("d343o"));
        message = chatMessageRepository.save(ChatMessage.builder()
            .sender(sender).recipient(recipient).content("D343 hello").build());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        chatMessageRepository.deleteById(message.getId());
        userRepository.deleteAll(List.of(sender, recipient, outsider));
    }

    @Test
    @DisplayName("D343: the recipient reads by own username, 200 with sender and recipient populated")
    void recipientByOwnUsername() throws Exception {
        mockMvc.perform(get("/chat/messages/by-recipient-username")
                .param("username", recipient.getUsername()).with(as(recipient)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(1)))
            .andExpect(jsonPath("$[0].senderId").value(sender.getId().toString()))
            .andExpect(jsonPath("$[0].senderName").isNotEmpty())
            .andExpect(jsonPath("$[0].recipientId").value(recipient.getId().toString()))
            .andExpect(jsonPath("$[0].recipientName").isNotEmpty());
    }

    @Test
    @DisplayName("D343: the sender reads by own email, 200 with sender and recipient populated")
    void senderByOwnEmail() throws Exception {
        mockMvc.perform(get("/chat/messages/by-sender-email")
                .param("email", sender.getEmail()).with(as(sender)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(1)))
            .andExpect(jsonPath("$[0].senderId").value(sender.getId().toString()))
            .andExpect(jsonPath("$[0].senderName").isNotEmpty())
            .andExpect(jsonPath("$[0].recipientId").value(recipient.getId().toString()))
            .andExpect(jsonPath("$[0].recipientName").isNotEmpty());
    }

    @Test
    @DisplayName("D343: a non-participant asking for someone else's address gets 404")
    void nonParticipantGets404() throws Exception {
        mockMvc.perform(get("/chat/messages/by-recipient-username")
                .param("username", recipient.getUsername()).with(as(outsider)))
            .andExpect(status().isNotFound());
        mockMvc.perform(get("/chat/messages/by-sender-email")
                .param("email", sender.getEmail()).with(as(outsider)))
            .andExpect(status().isNotFound());
    }

    private static User user(String prefix) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return User.builder()
            .username(prefix + suffix)
            .passwordHash("hashed-password")
            .email(prefix + suffix + "@example.test")
            .firstName(prefix + "FN")
            .lastName("User" + suffix)
            .phoneNumber("+2267" + Math.abs(suffix.hashCode() % 10_000_000))
            .isActive(true)
            .build();
    }

    private static RequestPostProcessor as(User user) {
        List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_DOCTOR"));
        CustomUserDetails principal = new CustomUserDetails(
            user.getId(), user.getUsername(), user.getPasswordHash(), true, authorities);
        Authentication auth = new UsernamePasswordAuthenticationToken(principal, user.getPasswordHash(), authorities);
        return request -> {
            SecurityContextHolder.getContext().setAuthentication(auth);
            request.setUserPrincipal(auth);
            return authentication(auth).postProcessRequest(request);
        };
    }
}
