/*
 * Copyright 2016 Lukáš Petrovický
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.github.triceo.robozonky;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import com.github.triceo.robozonky.remote.BlockedAmount;
import com.github.triceo.robozonky.remote.InvestingZonkyApi;
import com.github.triceo.robozonky.remote.Investment;
import com.github.triceo.robozonky.remote.Loan;
import com.github.triceo.robozonky.remote.Statistics;
import com.github.triceo.robozonky.remote.ZonkyApi;
import com.github.triceo.robozonky.remote.ZotifyApi;
import com.github.triceo.robozonky.strategy.InvestmentStrategy;
import org.assertj.core.api.Assertions;
import org.assertj.core.api.SoftAssertions;
import org.hamcrest.BaseMatcher;
import org.hamcrest.Description;
import org.jboss.resteasy.spi.BadRequestException;
import org.junit.Test;
import org.mockito.Matchers;
import org.mockito.Mockito;

public class InvestorTest {

    private static Investment getInvestmentWithLoanId(final int id) {
        return new Investment(InvestorTest.getMockLoanWithId(id), 200);
    }

    private static Loan getMockLoanWithId(final int id) {
        return InvestorTest.getMockLoanWithIdAndAmount(id, 1000);
    }

    private static Loan getMockLoanWithIdAndAmount(final int id, final int amount) {
        final Loan l = Mockito.mock(Loan.class);
        Mockito.when(l.getId()).thenReturn(id);
        Mockito.when(l.getAmount()).thenReturn((double)amount);
        return l;
    }

    @Test
    public void mergingTwoInvestmentCollectionsWorksProperly() {
        final Investment I1 = InvestorTest.getInvestmentWithLoanId(1);
        final Investment I2 = InvestorTest.getInvestmentWithLoanId(2);
        final Investment I3 = InvestorTest.getInvestmentWithLoanId(3);

        // two identical investments will result in one
        final List<Investment> a = Arrays.asList(I1, I2);
        final List<Investment> b = Arrays.asList(I2, I3);
        Assertions.assertThat(Investor.mergeInvestments(a, b)).containsExactly(I1, I2, I3);

        // toy around with empty lists
        Assertions.assertThat(Investor.mergeInvestments(Collections.emptyList(), Collections.emptyList())).isEmpty();
        Assertions.assertThat(Investor.mergeInvestments(Collections.emptyList(), a))
                .containsExactly((Investment[]) a.toArray());
        Assertions.assertThat(Investor.mergeInvestments(b, Collections.emptyList()))
                .containsExactly((Investment[]) b.toArray());

        // standard merging also works
        final List<Investment> c = Collections.singletonList(I3);
        Assertions.assertThat(Investor.mergeInvestments(a, c)).containsExactly(I1, I2, I3);

        // reverse-order merging works
        final List<Investment> d = Arrays.asList(I2, I1);
        Assertions.assertThat(Investor.mergeInvestments(a, d)).containsExactly(I1, I2);
    }

    @Test
    public void recoverOnFailedInvestment() {
        // the strategy will recommend two different investments
        final Loan mockLoan1 = InvestorTest.getMockLoanWithId(1);
        final Loan mockLoan2 = InvestorTest.getMockLoanWithId(2);
        final InvestmentStrategy strategyMock = Mockito.mock(InvestmentStrategy.class);
        Mockito.when(strategyMock.getMatchingLoans(Matchers.any(), Matchers.any()))
                .thenReturn(Arrays.asList(mockLoan1, mockLoan2));
        Mockito.when(strategyMock.recommendInvestmentAmount(Matchers.any(), Matchers.any())).thenReturn(400);
        // fail on the first loan, accept the second
        final InvestingZonkyApi mockApi = Mockito.mock(InvestingZonkyApi.class);
        Mockito.doThrow(BadRequestException.class)
                .when(mockApi).invest(Matchers.argThat(new InvestorTest.InvestmentBaseMatcher(mockLoan1)));
        // finally test
        final Investor investor = new Investor(mockApi, Mockito.mock(ZotifyApi.class), strategyMock,
                BigDecimal.valueOf(1000));
        final Optional<Investment> result = investor.investOnce(BigDecimal.valueOf(1000), new Statistics(),
                Collections.emptyList());
        // check that the first loan properly failed over to the second
        Mockito.verify(mockApi, Mockito.times(2)).invest(Matchers.any());
        final SoftAssertions softly = new SoftAssertions();
        softly.assertThat(result).isPresent();
        softly.assertThat(result.get().getLoanId()).isEqualTo(mockLoan2.getId());
        softly.assertAll();
    }

    @Test
    public void investOnRecommendations() {
        // prepare loans so that only one will survive
        final BigDecimal balance = BigDecimal.valueOf(1000);
        final Loan overBalance = InvestorTest.getMockLoanWithIdAndAmount(1, balance.intValue() * 2);
        final Loan underMinimum =
                InvestorTest.getMockLoanWithIdAndAmount(2, InvestmentStrategy.MINIMAL_INVESTMENT_ALLOWED);
        final int amount = 500;
        final Loan overAmount = InvestorTest.getMockLoanWithIdAndAmount(3, amount);
        final Loan success = InvestorTest.getMockLoanWithIdAndAmount(5, amount);
        final Statistics stats = Mockito.mock(Statistics.class);
        Mockito.when(stats.getRiskPortfolio()).thenReturn(Collections.emptyList());
        // prepare pre-conditions for the above loans
        final InvestmentStrategy strategy = Mockito.mock(InvestmentStrategy.class);
        Mockito.when(strategy.recommendInvestmentAmount(Matchers.eq(overBalance), Matchers.any()))
                .thenReturn(balance.intValue() + 1);
        Mockito.when(strategy.recommendInvestmentAmount(Matchers.eq(underMinimum), Matchers.any())).thenReturn(0);
        Mockito.when(strategy.recommendInvestmentAmount(Matchers.eq(overAmount), Matchers.any()))
                .thenReturn(amount + 1);
        Mockito.when(strategy.recommendInvestmentAmount(Matchers.eq(success), Matchers.any())).thenReturn(amount / 2);
        final InvestingZonkyApi api = Mockito.mock(InvestingZonkyApi.class);
        final ZotifyApi zotifyApi = Mockito.mock(ZotifyApi.class);
        // and now actually test that the succeeding loan will be invested into ...
        final Investor i = new Investor(api, zotifyApi, strategy, balance);
        Mockito.when(strategy.getMatchingLoans(Matchers.any(), Matchers.any()))
                .thenReturn(Arrays.asList(overBalance, underMinimum, overAmount, success));
        final Optional<Investment> result = i.investOnce(balance, stats, Collections.emptyList());
        Assertions.assertThat(result).isPresent();
        Assertions.assertThat(result.get().getLoanId()).isEqualTo(success.getId());
        Mockito.verify(api, Mockito.times(1)).invest(Matchers.any());
        // ... no matter which place it takes
        Mockito.when(strategy.getMatchingLoans(Matchers.any(), Matchers.any()))
                .thenReturn(Arrays.asList(success, overBalance, underMinimum, overAmount));
        final Optional<Investment> result2 = i.investOnce(balance, stats, Collections.emptyList());
        Assertions.assertThat(result2).isPresent();
        Assertions.assertThat(result2.get().getLoanId()).isEqualTo(success.getId());
        Mockito.verify(api, Mockito.times(2)).invest(Matchers.any());
        // ... even when nothing is accepted
        final Loan alreadyPresent = InvestorTest.getMockLoanWithIdAndAmount(6, 10000);
        Mockito.when(strategy.getMatchingLoans(Matchers.any(), Matchers.any()))
                .thenReturn(Arrays.asList(overBalance, underMinimum, overAmount, alreadyPresent));
        final Investment alreadyPresentInvestment = new Investment(alreadyPresent, 200);
        final Optional<Investment> result3 =
                i.investOnce(balance, stats, Collections.singletonList(alreadyPresentInvestment));
        Assertions.assertThat(result3).isEmpty();
    }

    private static class InvestmentBaseMatcher extends BaseMatcher<Investment> {
        private final Loan matching;

        public InvestmentBaseMatcher(final Loan matching) {
            this.matching = matching;
        }

        @Override
        public void describeTo(final Description description) {
            description.appendText("Matches only the investment matching a specified loan.");
        }

        @Override
        public boolean matches(final Object item) {
            return ((Investment) item).getLoanId() == matching.getId();
        }
    }

    @Test
    public void properBlockedAmountRetrieval() {
        final int loan1id = 1, loan1amount = 100;
        final Loan l1 = InvestorTest.getMockLoanWithIdAndAmount(loan1id, loan1amount);
        final int loan2id = loan1id + 1, loan2amount = 200;
        final Loan l2 = InvestorTest.getMockLoanWithIdAndAmount(loan2id, loan2amount);
        final int loan3amount = 400;
        final ZonkyApi api = Mockito.mock(ZonkyApi.class);
        Mockito.when(api.getLoan(Matchers.eq(loan1id))).thenReturn(l1);
        Mockito.when(api.getLoan(Matchers.eq(loan2id))).thenReturn(l2);
        Mockito.when(api.getBlockedAmounts(Matchers.anyInt(), Matchers.anyInt())).thenReturn(
                Arrays.asList(new BlockedAmount(0, 1000), new BlockedAmount(loan1id, loan1amount),
                        new BlockedAmount(loan2id, loan2amount), new BlockedAmount(loan1id, loan3amount))
        );
        final List<Investment> result = Investor.retrieveInvestmentsRepresentedByBlockedAmounts(api);
        // the 0 ID blocked amount is Zonky's investors' fee, which should not be looked up as a loan
        final SoftAssertions softly = new SoftAssertions();
        softly.assertThat(result).hasSize(2);
        final Investment i1 = result.get(0);
        softly.assertThat(i1.getLoanId()).isEqualTo(loan1id);
        softly.assertThat(i1.getAmount()).isEqualTo(loan1amount + loan3amount);
        final Investment i2 = result.get(1);
        softly.assertThat(i2.getLoanId()).isEqualTo(loan2id);
        softly.assertThat(i2.getAmount()).isEqualTo(loan2amount);
        softly.assertAll();
    }

}
