/*
 * Copyright 2013, 2014 Megion Research and Development GmbH
 *
 * Licensed under the Microsoft Reference Source License (MS-RSL)
 *
 * This license governs use of the accompanying software. If you use the software, you accept this license.
 * If you do not accept the license, do not use the software.
 *
 * 1. Definitions
 * The terms "reproduce," "reproduction," and "distribution" have the same meaning here as under U.S. copyright law.
 * "You" means the licensee of the software.
 * "Your company" means the company you worked for when you downloaded the software.
 * "Reference use" means use of the software within your company as a reference, in read only form, for the sole purposes
 * of debugging your products, maintaining your products, or enhancing the interoperability of your products with the
 * software, and specifically excludes the right to distribute the software outside of your company.
 * "Licensed patents" means any Licensor patent claims which read directly on the software as distributed by the Licensor
 * under this license.
 *
 * 2. Grant of Rights
 * (A) Copyright Grant- Subject to the terms of this license, the Licensor grants you a non-transferable, non-exclusive,
 * worldwide, royalty-free copyright license to reproduce the software for reference use.
 * (B) Patent Grant- Subject to the terms of this license, the Licensor grants you a non-transferable, non-exclusive,
 * worldwide, royalty-free patent license under licensed patents for reference use.
 *
 * 3. Limitations
 * (A) No Trademark License- This license does not grant you any rights to use the Licensor’s name, logo, or trademarks.
 * (B) If you begin patent litigation against the Licensor over patents that you think may apply to the software
 * (including a cross-claim or counterclaim in a lawsuit), your license to the software ends automatically.
 * (C) The software is licensed "as-is." You bear the risk of using it. The Licensor gives no express warranties,
 * guarantees or conditions. You may have additional consumer rights under your local laws which this license cannot
 * change. To the extent permitted under your local laws, the Licensor excludes the implied warranties of merchantability,
 * fitness for a particular purpose and non-infringement.
 */

package com.mycelium.wallet.activity;

import java.text.DateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.LinearLayout.LayoutParams;
import android.widget.TextView;
import android.widget.Toast;

import com.mrd.bitlib.StandardTransactionBuilder;
import com.mrd.bitlib.model.*;

import com.google.common.base.Optional;
import com.mrd.bitlib.model.Address;
import com.mrd.bitlib.util.Sha256Hash;
import com.mycelium.wallet.BitcoinUri;
import com.mycelium.wallet.MbwManager;
import com.mycelium.wallet.R;
import com.mycelium.wallet.Utils;
import com.mycelium.wallet.activity.util.AddressLabel;
import com.mycelium.wallet.activity.util.TransactionDetailsLabel;
import com.mycelium.wapi.model.TransactionDetails;
import com.mycelium.wapi.model.TransactionStatus;
import com.mycelium.wapi.wallet.WalletAccount;


public class TransactionDetailsActivity extends Activity {

   @SuppressWarnings("deprecation")
   private static final LayoutParams FPWC = new LayoutParams(LayoutParams.FILL_PARENT, LayoutParams.WRAP_CONTENT, 1);
   private static final LayoutParams WCWC = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, 1);
   private TransactionDetails _tx;
   private int _white_color;
   private MbwManager _mbwManager;
   private Address _receivingAddress;
   private WalletAccount _account;

    private TransactionStatus _transactionStatus;
    private Transaction _transaction;
    private StandardTransactionBuilder.UnsignedTransaction _unsigned;
   private String _transactionInfoTemplate;

   /**
    * Called when the activity is first created.
    */
   @SuppressLint("ShowToast")
   @Override
   public void onCreate(Bundle savedInstanceState) {
      this.getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
      super.onCreate(savedInstanceState);

      _white_color = getResources().getColor(R.color.white);
      setContentView(R.layout.transaction_details_activity);
      _mbwManager = MbwManager.getInstance(this.getApplication());

      Sha256Hash txid = (Sha256Hash) getIntent().getSerializableExtra("transaction");
      _tx = _mbwManager.getSelectedAccount().getTransactionDetails(txid);
       _transaction = _mbwManager.getSelectedAccount().getTransaction(txid);
       _account = _mbwManager.getSelectedAccount().getId();


       // Clipboard
       findViewById(R.id.btClipboard2).setOnClickListener(clipboardClickListener);

      updateUi();
   }

    private View.OnClickListener clipboardClickListener = new View.OnClickListener() {

        @Override
        public void onClick(View arg0) {
            BitcoinUri uri = getUriFromClipboard();
            if (uri != null) {
                Toast.makeText(TransactionDetailsActivity.this, getResources().getString(R.string.using_address_from_clipboard),
                        Toast.LENGTH_SHORT).show();
                _receivingAddress = uri.address;
                if (uri.amount != null) {
                    //ignore amount
                   // _amountToSend = uri.amount;
                }
                _transactionStatus = tryCreateUnsignedTransaction();
                updateUi();
            }
        }
    };
    private List<UnspentTransactionOutput> UnspentTransactionOutputfromTransactionInputs(){
        UnspentTransactionOutput[] utxo = new UnspentTransactionOutput[_transaction.inputs.length];
        List<UnspentTransactionOutput> utxoList;

        for (int i=0; i < _transaction.inputs.length;i++)
            utxo[i]=new UnspentTransactionOutput(_transaction.inputs[i].outPoint, -1, _tx.inputs[i].value,new ScriptOutputStandard(_tx.inputs[i].address.getTypeSpecificBytes())) ;
        utxoList= Arrays.asList(utxo);
        return utxoList;
    }
    private TransactionStatus tryCreateUnsignedTransaction() {
        _unsigned = null;
        Long _amountToSend;
        if ( _receivingAddress == null) {
            return TransactionStatus.MissingArguments;
        }

        // Create the unsigned transaction
        try {
            List<UnspentTransactionOutput> utxo = UnspentTransactionOutputfromTransactionInputs();
            _amountToSend = sum(_tx.outputs)- getFee(_tx)/10;
            WalletAccount.Receiver receiver = new WalletAccount.Receiver(_receivingAddress, _amountToSend);
            _unsigned = _account.createUnsignedTransaction(utxo,Arrays.asList(receiver), _mbwManager.getMinerFee().kbMinerFee);
            return TransactionStatus.OK;
        } catch (StandardTransactionBuilder.InsufficientFundsException e) {
            Toast.makeText(this, getResources().getString(R.string.insufficient_funds), Toast.LENGTH_LONG).show();
            return TransactionStatus.InsufficientFunds;
        } catch (StandardTransactionBuilder.OutputTooSmallException e1) {
            Toast.makeText(this, getResources().getString(R.string.amount_too_small), Toast.LENGTH_LONG).show();
            return TransactionStatus.OutputTooSmall;
        }
    }
    private BitcoinUri getUriFromClipboard() {
        String content = Utils.getClipboardString(TransactionDetailsActivity.this);
        if (content.length() == 0) {
            return null;
        }
        String string = content.trim();
        if (string.matches("[a-zA-Z0-9]*")) {
            // Raw format
            Address address = Address.fromString(string, _mbwManager.getNetwork());
            if (address == null) {
                return null;
            }
            return new BitcoinUri(address, null, null);
        } else {
            Optional<BitcoinUri> b = BitcoinUri.parse(string, _mbwManager.getNetwork());
            if (b.isPresent()) {
                // On URI format
                return b.get();
            }
        }
        return null;
    }



   private void updateUi() {
      // Set Hash
      TransactionDetailsLabel tvHash = ((TransactionDetailsLabel) findViewById(R.id.tvHash));
      tvHash.setTransaction(_tx);


      // Set Confirmed
      String confirmed;
      if (_tx.height > 0) {
         confirmed = getResources().getString(R.string.confirmed_in_block, _tx.height);
      } else {
         confirmed = getResources().getString(R.string.no);
      }
      ((TextView) findViewById(R.id.tvConfirmed)).setText(confirmed);

      // Set Date & Time
      Date date = new Date(_tx.time * 1000L);
      Locale locale = getResources().getConfiguration().locale;
      DateFormat dayFormat = DateFormat.getDateInstance(DateFormat.LONG, locale);
      String dateString = dayFormat.format(date);
      ((TextView) findViewById(R.id.tvDate)).setText(dateString);
      DateFormat hourFormat = DateFormat.getTimeInstance(DateFormat.LONG, locale);
      String timeString = hourFormat.format(date);
      ((TextView) findViewById(R.id.tvTime)).setText(timeString);

      // Set Inputs
      LinearLayout inputs = (LinearLayout) findViewById(R.id.llInputs);
      for (TransactionDetails.Item item : _tx.inputs) {
         inputs.addView(getItemView(item));
      }

      // Set Outputs
      LinearLayout outputs = (LinearLayout) findViewById(R.id.llOutputs);
      for (TransactionDetails.Item item : _tx.outputs) {
         outputs.addView(getItemView(item));
      }

      // Set Fee
      String fee = _mbwManager.getBtcValueString(getFee(_tx));
      ((TextView) findViewById(R.id.tvFee)).setText(fee);

   }

   private long getFee(TransactionDetails tx) {
      long inputs = sum(tx.inputs);
      long outputs = sum(tx.outputs);
      return inputs - outputs;
   }

   private long sum(TransactionDetails.Item[] items) {
      long sum = 0;
      for (TransactionDetails.Item item : items) {
         sum += item.value;
      }
      return sum;
   }

   private View getItemView(TransactionDetails.Item item) {
      // Create vertical linear layout
      LinearLayout ll = new LinearLayout(this);
      ll.setOrientation(LinearLayout.VERTICAL);
      ll.setLayoutParams(WCWC);

      if (item.isCoinbase) {
         // Coinbase input
         ll.addView(getValue(item.value, null));
         ll.addView(getCoinbaseText());
      } else {
         String address = item.address.toString();

         // Add BTC value
         ll.addView(getValue(item.value, address));

         AddressLabel adrLabel = new AddressLabel(this);
         adrLabel.setAddress(item.address);
         ll.addView(adrLabel);
      }
      ll.setPadding(10, 10, 10, 10);
      return ll;
   }


   private View getCoinbaseText() {
      TextView tv = new TextView(this);
      tv.setLayoutParams(FPWC);
      tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
      tv.setText(R.string.newly_generated_coins_from_coinbase);
      tv.setTextColor(_white_color);
      return tv;
   }

   private View getValue(long value, Object tag) {
      TextView tv = new TextView(this);
      tv.setLayoutParams(FPWC);
      tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
      tv.setText(_mbwManager.getBtcValueString(value));
      tv.setTextColor(_white_color);
      tv.setTag(tag);
      return tv;
   }

}