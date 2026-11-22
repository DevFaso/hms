package com.example.hms.service.support;

import com.example.hms.model.User;
import com.example.hms.repository.UserRepository;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** QA D3150 (r70a): a stored login is shown as the person's name, and never as the login. */
class UserDisplayNamesTest {

    private final UserRepository users = mock(UserRepository.class);
    private final UserDisplayNames names = new UserDisplayNames(users);

    private static User user(String username, String first, String last) {
        User user = new User();
        user.setUsername(username);
        user.setFirstName(first);
        user.setLastName(last);
        return user;
    }

    @Test
    void aLoginIsReadAsTheNameOfItsUser() {
        when(users.findByUsernameInIgnoreCase(List.of("test.receptionist.t2.r67")))
            .thenReturn(List.of(user("test.receptionist.t2.r67", "Awa", "Ouedraogo")));

        assertThat(names.ofUsername("Test.Receptionist.T2.r67 ")).isEqualTo("Awa Ouedraogo");
    }

    @Test
    void aLoginOfNobodyOrOfAUserWithNoNameIsNoName() {
        when(users.findByUsernameInIgnoreCase(any())).thenReturn(List.of(user("nameless", " ", null)));

        assertThat(names.ofUsername("nameless")).isNull();
        assertThat(names.ofUsername("gone")).isNull();
    }

    @Test
    void noLoginReadsNothing() {
        assertThat(names.ofUsername(null)).isNull();
        assertThat(names.ofUsername(" ")).isNull();
        Map<String, String> none = names.ofUsernames(Arrays.asList(null, ""));
        assertThat(none.get(null)).isNull();
        verify(users, never()).findByUsernameInIgnoreCase(any());
    }
}
