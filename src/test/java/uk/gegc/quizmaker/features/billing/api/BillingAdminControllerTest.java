package uk.gegc.quizmaker.features.billing.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uk.gegc.quizmaker.features.billing.application.StripePackSyncService;
import uk.gegc.quizmaker.features.billing.domain.model.ProductPack;
import uk.gegc.quizmaker.features.billing.infra.repository.ProductPackRepository;
import uk.gegc.quizmaker.features.user.domain.model.PermissionName;
import uk.gegc.quizmaker.shared.security.AppPermissionEvaluator;
import uk.gegc.quizmaker.shared.security.aspect.PermissionAspect;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(BillingAdminController.class)
@Import(PermissionAspect.class)
@EnableAspectJAutoProxy
class BillingAdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StripePackSyncService stripePackSyncService;

    @MockitoBean
    private ProductPackRepository productPackRepository;

    @MockitoBean
    private AppPermissionEvaluator appPermissionEvaluator;

    @Test
    @DisplayName("POST /api/v1/admin/billing/packs/sync: when SYSTEM_ADMIN then triggers sync and returns active packs")
    @WithMockUser(authorities = "SYSTEM_ADMIN")
    void syncStripePacks_whenSystemAdmin_thenReturnsActivePacks() throws Exception {
        when(appPermissionEvaluator.hasAnyPermission(PermissionName.SYSTEM_ADMIN)).thenReturn(true);

        ProductPack pack1 = new ProductPack();
        pack1.setId(UUID.randomUUID());
        pack1.setName("Starter Pack");
        pack1.setDescription("desc 1");
        pack1.setTokens(1000L);
        pack1.setPriceCents(400L);
        pack1.setCurrency("gbp");
        pack1.setStripePriceId("price_1");
        pack1.setActive(true);

        ProductPack pack2 = new ProductPack();
        pack2.setId(UUID.randomUUID());
        pack2.setName("Pro Pack");
        pack2.setDescription("desc 2");
        pack2.setTokens(10000L);
        pack2.setPriceCents(3000L);
        pack2.setCurrency("gbp");
        pack2.setStripePriceId("price_2");
        pack2.setActive(true);

        when(productPackRepository.findByActiveTrue()).thenReturn(List.of(pack2, pack1));

        mockMvc.perform(post("/api/v1/admin/billing/packs/sync")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("Starter Pack"))
                .andExpect(jsonPath("$[1].name").value("Pro Pack"));

        verify(stripePackSyncService, times(1)).syncActivePacks();
        verify(productPackRepository, times(1)).findByActiveTrue();
    }

    @Test
    @DisplayName("POST /api/v1/admin/billing/packs/sync: when missing SYSTEM_ADMIN then returns 403")
    @WithMockUser(authorities = "BILLING_READ")
    void syncStripePacks_whenMissingPermission_thenForbidden() throws Exception {
        when(appPermissionEvaluator.hasAnyPermission(PermissionName.SYSTEM_ADMIN)).thenReturn(false);

        mockMvc.perform(post("/api/v1/admin/billing/packs/sync")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());

        verifyNoInteractions(stripePackSyncService);
    }
}
