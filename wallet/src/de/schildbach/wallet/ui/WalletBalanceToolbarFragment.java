/*
 * Copyright 2011-2015 the original author or authors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package de.schildbach.wallet.ui;

import android.app.Activity;
import android.content.Context;
import android.database.Cursor;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.support.v4.app.LoaderManager;
import android.support.v4.content.Loader;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import org.bitcoinj.core.Coin;
import org.bitcoinj.core.MasternodeSync;
import org.bitcoinj.core.Wallet;
import org.bitcoinj.utils.Fiat;

import javax.annotation.Nullable;

import de.schildbach.wallet.Configuration;
import de.schildbach.wallet.Constants;
import de.schildbach.wallet.ExchangeRatesProvider;
import de.schildbach.wallet.ExchangeRatesProvider.ExchangeRate;
import de.schildbach.wallet.WalletApplication;
import de.schildbach.wallet.service.BlockchainState;
import de.schildbach.wallet.service.BlockchainStateLoader;
import hashengineering.darkcoin.wallet.R;

/**
 * @author Andreas Schildbach
 */
public final class WalletBalanceToolbarFragment extends Fragment
{
	private WalletApplication application;
	private Activity activity;
	private Configuration config;
	private Wallet wallet;
	private LoaderManager loaderManager;

	private View viewBalance;
	private View progressView;
	private CurrencyTextView viewBalanceBtc;
	private View viewBalanceTooMuch;
	private CurrencyTextView viewBalanceLocal;
    private TextView appBarMessageView;

	private boolean showLocalBalance;

    private String progressMessage;

	@Nullable
	private Coin balance = null;
	@Nullable
	private ExchangeRate exchangeRate = null;
	@Nullable
	private BlockchainState blockchainState = null;
    @Nullable
    private int masternodeSyncStatus = MasternodeSync.MASTERNODE_SYNC_FINISHED;

	private static final int ID_BALANCE_LOADER = 0;
	private static final int ID_RATE_LOADER = 1;
	private static final int ID_BLOCKCHAIN_STATE_LOADER = 2;
    private static final int ID_MASTERNODE_SYNC_LOADER = 3;

	private static final long BLOCKCHAIN_UPTODATE_THRESHOLD_MS = DateUtils.HOUR_IN_MILLIS;
	private static final Coin TOO_MUCH_BALANCE_THRESHOLD = Coin.COIN.multiply(350);

    @Override
    public void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
    }

	@Override
	public void onAttach(final Context context)
	{
		super.onAttach(context);

		this.activity = (Activity) context;
		this.application = (WalletApplication) activity.getApplication();
		this.config = application.getConfiguration();
		this.wallet = application.getWallet();
		this.loaderManager = getLoaderManager();

		showLocalBalance = getResources().getBoolean(R.bool.show_local_balance);
	}

    @Override
    public void onActivityCreated(@android.support.annotation.Nullable Bundle savedInstanceState)
    {
        super.onActivityCreated(savedInstanceState);
        this.appBarMessageView = (TextView) activity.findViewById(R.id.toolbar_message);
    }

    @Override
	public View onCreateView(final LayoutInflater inflater, final ViewGroup container, final Bundle savedInstanceState)
	{
		return inflater.inflate(R.layout.wallet_balance_toolbar_fragment, container, false);
	}

	@Override
	public void onViewCreated(final View view, final Bundle savedInstanceState)
	{
		super.onViewCreated(view, savedInstanceState);

		progressView = view.findViewById(R.id.progress);

		viewBalance = view.findViewById(R.id.wallet_balance);

		viewBalanceBtc = (CurrencyTextView) view.findViewById(R.id.wallet_balance_btc);
		viewBalanceBtc.setPrefixScaleX(0.9f);

		viewBalanceTooMuch = view.findViewById(R.id.wallet_balance_too_much_warning);

		viewBalanceLocal = (CurrencyTextView) view.findViewById(R.id.wallet_balance_local);
		viewBalanceLocal.setInsignificantRelativeSize(1);
		viewBalanceLocal.setStrikeThru(Constants.TEST);
	}

    @Override
	public void onResume()
	{
		super.onResume();

		loaderManager.initLoader(ID_BALANCE_LOADER, null, balanceLoaderCallbacks);
		loaderManager.initLoader(ID_RATE_LOADER, null, rateLoaderCallbacks);
		loaderManager.initLoader(ID_BLOCKCHAIN_STATE_LOADER, null, blockchainStateLoaderCallbacks);
        loaderManager.initLoader(ID_MASTERNODE_SYNC_LOADER, null, masternodeSyncLoaderCallbacks);

		updateView();
	}

	@Override
	public void onPause()
	{
		loaderManager.destroyLoader(ID_BLOCKCHAIN_STATE_LOADER);
		loaderManager.destroyLoader(ID_RATE_LOADER);
		loaderManager.destroyLoader(ID_BALANCE_LOADER);
		loaderManager.destroyLoader(ID_MASTERNODE_SYNC_LOADER);

		super.onPause();
	}

	private void updateView()
	{
		if (!isAdded())
			return;

		final boolean showProgress;

		if (blockchainState != null && blockchainState.bestChainDate != null)
		{
			final long blockchainLag = System.currentTimeMillis() - blockchainState.bestChainDate.getTime();
			final boolean blockchainUptodate = blockchainLag < BLOCKCHAIN_UPTODATE_THRESHOLD_MS;
			final boolean noImpediments = blockchainState.impediments.isEmpty();

			showProgress = !(blockchainUptodate || !blockchainState.replaying);

			final String downloading = getString(noImpediments ? R.string.blockchain_state_progress_downloading
					: R.string.blockchain_state_progress_stalled);

			if (blockchainLag < 2 * DateUtils.DAY_IN_MILLIS)
			{
				final long hours = blockchainLag / DateUtils.HOUR_IN_MILLIS;
                progressMessage = getString(R.string.blockchain_state_progress_hours, downloading, hours);
			}
			else if (blockchainLag < 2 * DateUtils.WEEK_IN_MILLIS)
			{
				final long days = blockchainLag / DateUtils.DAY_IN_MILLIS;
                progressMessage = getString(R.string.blockchain_state_progress_days, downloading, days);
			}
			else if (blockchainLag < 90 * DateUtils.DAY_IN_MILLIS)
			{
				final long weeks = blockchainLag / DateUtils.WEEK_IN_MILLIS;
                progressMessage = getString(R.string.blockchain_state_progress_weeks, downloading, weeks);
			}
			else
			{
				final long months = blockchainLag / (30 * DateUtils.DAY_IN_MILLIS);
                progressMessage = getString(R.string.blockchain_state_progress_months, downloading, months);
			}
		}
		else
		{
			showProgress = false;
		}

		if (!showProgress)
		{
			viewBalance.setVisibility(View.VISIBLE);

			if (!showLocalBalance)
				viewBalanceLocal.setVisibility(View.GONE);

			if (balance != null)
			{
				viewBalanceBtc.setVisibility(View.VISIBLE);
				viewBalanceBtc.setFormat(config.getFormat());
				viewBalanceBtc.setAmount(balance);

                updateBalanceTooMuchWarning();

				if (showLocalBalance)
				{
					if (exchangeRate != null)
					{
						final Fiat localValue = exchangeRate.rate.coinToFiat(balance);
						viewBalanceLocal.setVisibility(View.VISIBLE);
						viewBalanceLocal.setFormat(Constants.LOCAL_FORMAT.code(0, Constants.PREFIX_ALMOST_EQUAL_TO + exchangeRate.getCurrencyCode()));
						viewBalanceLocal.setAmount(localValue);
					}
					else
					{
						viewBalanceLocal.setVisibility(View.INVISIBLE);
					}
				}
			}
			else
			{
				viewBalanceBtc.setVisibility(View.INVISIBLE);
			}

            if(masternodeSyncStatus != MasternodeSync.MASTERNODE_SYNC_FINISHED)
            {
                progressView.setVisibility(View.VISIBLE);
                viewBalance.setVisibility(View.INVISIBLE);
                String syncStatus = wallet.getContext().masternodeSync.getSyncStatus();
                showAppBarMessage(syncStatus);
            } else {
				//Show sync status of Masternodes
				//int masternodesLoaded = wallet.getContext().masternodeSync.mapSeenSyncMNB.size();
				//int totalMasternodes = wallet.getContext().masternodeSync.masterNodeCountFromNetwork();

				//if(totalMasternodes == 0 || totalMasternodes < masternodesLoaded + 100) {
					progressView.setVisibility(View.GONE);
					showAppBarMessage(null);
				//}
				//else
				//{
					//showAppBarMessage("Masternodes Loaded: " + masternodesLoaded *100 /totalMasternodes +"%");
				//	showAppBarMessage("Masternodes Loaded: " + masternodesLoaded +" of "+ totalMasternodes);
				//}
            }
        }
		else
		{
            showAppBarMessage(progressMessage);
            progressView.setVisibility(View.VISIBLE);
            progressView.setOnClickListener(new OnClickListener()
            {
                @Override
                public void onClick(View v)
                {
                    Toast.makeText(getContext(), progressMessage, Toast.LENGTH_LONG).show();
                }
            });
            viewBalance.setVisibility(View.INVISIBLE);
        }
    }

    private void showAppBarMessage(CharSequence message) {
        if (message != null) {
            appBarMessageView.setVisibility(View.VISIBLE);
            appBarMessageView.setText(message);
        } else {
            appBarMessageView.setVisibility(View.GONE);
        }
    }

    private void updateBalanceTooMuchWarning() {
        if (balance == null)
            return;

        final boolean tooMuch = balance.isGreaterThan(TOO_MUCH_BALANCE_THRESHOLD);
        viewBalanceTooMuch.setVisibility(tooMuch ? View.VISIBLE : View.GONE);
        if (tooMuch)
        {
            viewBalance.setOnClickListener(new OnClickListener()
            {
                @Override
                public void onClick(View v)
                {
                    Toast.makeText(getContext(), getString(R.string.wallet_balance_fragment_too_much), Toast.LENGTH_LONG).show();
                }
            });
        }
    }

	private final LoaderManager.LoaderCallbacks<BlockchainState> blockchainStateLoaderCallbacks = new LoaderManager.LoaderCallbacks<BlockchainState>()
	{
		@Override
		public Loader<BlockchainState> onCreateLoader(final int id, final Bundle args)
		{
			return new BlockchainStateLoader(activity);
		}

		@Override
		public void onLoadFinished(final Loader<BlockchainState> loader, final BlockchainState blockchainState)
		{
			WalletBalanceToolbarFragment.this.blockchainState = blockchainState;

			updateView();
		}

		@Override
		public void onLoaderReset(final Loader<BlockchainState> loader)
		{
		}
	};

	private final LoaderManager.LoaderCallbacks<Coin> balanceLoaderCallbacks = new LoaderManager.LoaderCallbacks<Coin>()
	{
		@Override
		public Loader<Coin> onCreateLoader(final int id, final Bundle args)
		{
			return new WalletBalanceLoader(activity, wallet);
		}

		@Override
		public void onLoadFinished(final Loader<Coin> loader, final Coin balance)
		{
			WalletBalanceToolbarFragment.this.balance = balance;

			updateView();
		}

		@Override
		public void onLoaderReset(final Loader<Coin> loader)
		{
		}
	};

	private final LoaderManager.LoaderCallbacks<Cursor> rateLoaderCallbacks = new LoaderManager.LoaderCallbacks<Cursor>()
	{
		@Override
		public Loader<Cursor> onCreateLoader(final int id, final Bundle args)
		{
			return new ExchangeRateLoader(activity, config);
		}

		@Override
		public void onLoadFinished(final Loader<Cursor> loader, final Cursor data)
		{
			if (data != null && data.getCount() > 0)
			{
				data.moveToFirst();
				exchangeRate = ExchangeRatesProvider.getExchangeRate(data);
				updateView();
			}
		}

		@Override
		public void onLoaderReset(final Loader<Cursor> loader)
		{
		}
	};
	private final LoaderManager.LoaderCallbacks<Integer> masternodeSyncLoaderCallbacks = new LoaderManager.LoaderCallbacks<Integer>()
	{
		@Override
		public Loader<Integer> onCreateLoader(final int id, final Bundle args)
		{
			return new MasternodeSyncLoader(activity, wallet);
		}

		@Override
		public void onLoadFinished(final Loader<Integer> loader, final Integer newStatus)
		{
			WalletBalanceToolbarFragment.this.masternodeSyncStatus = newStatus;

			updateView();

		}

		@Override
		public void onLoaderReset(final Loader<Integer> loader)
		{
		}
	};
}
