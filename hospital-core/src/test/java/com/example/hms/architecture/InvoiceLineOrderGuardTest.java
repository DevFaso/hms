package com.example.hms.architecture;

import com.example.hms.model.BillingInvoice;
import com.example.hms.model.InvoiceItem;
import com.example.hms.repository.InvoiceItemRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D2709, the guard of the class: every query that lists the lines of ONE invoice states its order. A list with
 * no order comes back in the table's physical order, which an update changes: the lines of a draft were shuffled
 * when the invoice was issued.
 */
@DisplayName("Invoice lines: every list of one invoice's lines is ordered")
class InvoiceLineOrderGuardTest {

    @Test
    void everyFinderOfOneInvoicesLinesStatesItsOrder() {
        List<String> unordered = new ArrayList<>();
        int checked = 0;
        for (Method method : InvoiceItemRepository.class.getDeclaredMethods()) {
            boolean listsLines = List.class.isAssignableFrom(method.getReturnType())
                && method.getGenericReturnType().getTypeName().contains(InvoiceItem.class.getName());
            boolean ofOneInvoice = method.getName().startsWith("findByBillingInvoice")
                && method.getParameterCount() == 1;
            if (!listsLines || !ofOneInvoice) {
                continue;
            }
            checked++;
            Query query = method.getAnnotation(Query.class);
            boolean ordered = (query != null && query.value().toUpperCase(Locale.ROOT).contains("ORDER BY"))
                || method.getName().contains("OrderBy");
            if (!ordered) {
                unordered.add(method.getName());
            }
        }
        assertThat(checked).as("the finders of one invoice's lines").isGreaterThanOrEqualTo(2);
        assertThat(unordered).as("list an invoice's lines in posting order (createdAt, id)").isEmpty();
    }

    @Test
    void anInvoiceKeepsItsLinesInTheOrderTheyWereAdded() {
        BillingInvoice invoice = new BillingInvoice();
        invoice.setInvoiceItems(new java.util.LinkedHashSet<>());
        String[] posted = {"Consultation", "NFS", "Widal", "Radio thorax", "Pansement", "Suture", "Injection"};
        for (String label : posted) {
            InvoiceItem line = new InvoiceItem();
            line.setId(java.util.UUID.randomUUID());
            line.setItemDescription(label);
            line.setQuantity(1);
            line.setUnitPrice(BigDecimal.ONE);
            line.setTotalPrice(BigDecimal.ONE);
            invoice.addItem(line);
        }
        assertThat(invoice.getInvoiceItems()).extracting(InvoiceItem::getItemDescription).containsExactly(posted);
        // The default collection of a new invoice keeps that order too (it was a HashSet).
        assertThat(BillingInvoice.builder().build().getInvoiceItems()).isInstanceOf(java.util.LinkedHashSet.class);
        assertThat(new BillingInvoice().getInvoiceItems()).isInstanceOf(java.util.LinkedHashSet.class);
    }
}
