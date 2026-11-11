package com.example.hms.repository;

import com.example.hms.enums.InBasketItemStatus;
import com.example.hms.enums.InBasketItemType;
import com.example.hms.enums.OrganizationType;
import com.example.hms.model.Hospital;
import com.example.hms.model.InBasketItem;
import com.example.hms.model.Organization;
import com.example.hms.model.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D931 on H2 (tables built from the entities): when a correction
 * supersedes a released result, every item of the old result that nobody has
 * acknowledged is removed - an UNREAD and a READ one alike, since either would
 * open on a result the clinician can no longer read - and an ACKNOWLEDGED item
 * stays as the review record. Items of another reference are untouched.
 */
@DataJpaTest
@ActiveProfiles("test")
@org.springframework.context.annotation.Import(com.example.hms.security.EncryptionKeyHolder.class)
@DisplayName("Superseded result: unacknowledged in-basket items removed (QA D931)")
class InBasketSupersededItemsRepositoryTest {

    @Autowired private InBasketItemRepository inBasketItemRepository;
    @Autowired private TestEntityManager em;

    @Test
    void everyUnacknowledgedItemOfTheSupersededResultIsRemovedAndAnAcknowledgedOneStays() {
        Organization organization = em.persist(Organization.builder()
                .name("In-basket Network").code("ORG-IB").type(OrganizationType.HOSPITAL_CHAIN).active(true).build());
        Hospital hospital = em.persist(Hospital.builder()
                .name("In-basket Hospital").code("HIB1")
                .city("Ouagadougou").country("Burkina Faso").address("1 Main St")
                .phoneNumber("+22655503").email("hib1@hospital.test")
                .organization(organization).build());
        User doctor = em.persist(User.builder()
                .username("doctor-ib").passwordHash("hashed-password")
                .email("doctor@inbasket.test").firstName("Doc").lastName("User")
                .phoneNumber("+22677123456").isActive(true).build());
        UUID superseded = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        InBasketItem unread = em.persist(item(doctor, hospital, superseded, InBasketItemStatus.UNREAD));
        InBasketItem read = em.persist(item(doctor, hospital, superseded, InBasketItemStatus.READ));
        InBasketItem acknowledged = em.persist(item(doctor, hospital, superseded, InBasketItemStatus.ACKNOWLEDGED));
        InBasketItem otherResult = em.persist(item(doctor, hospital, other, InBasketItemStatus.UNREAD));
        em.flush();
        em.clear();

        int removed = inBasketItemRepository.deleteOpenByReference(superseded, "LAB_RESULT");

        assertThat(removed).isEqualTo(2);
        assertThat(inBasketItemRepository.findById(unread.getId())).isEmpty();
        assertThat(inBasketItemRepository.findById(read.getId())).isEmpty();
        assertThat(inBasketItemRepository.findById(acknowledged.getId())).isPresent();
        assertThat(inBasketItemRepository.findById(otherResult.getId())).isPresent();
    }

    private static InBasketItem item(User recipient, Hospital hospital, UUID referenceId, InBasketItemStatus status) {
        InBasketItem item = new InBasketItem();
        item.setRecipientUser(recipient);
        item.setHospital(hospital);
        item.setItemType(InBasketItemType.RESULT);
        item.setStatus(status);
        item.setPriority(com.example.hms.enums.InBasketPriority.NORMAL);
        item.setTitle("TDR paludisme");
        item.setReferenceId(referenceId);
        item.setReferenceType("LAB_RESULT");
        return item;
    }
}
