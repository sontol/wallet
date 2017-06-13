/*
 * Copyright 2013, 2014 Megion Research & Development GmbH
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

package com.mrd.bitlib;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;
import com.google.common.collect.Ordering;
import com.mrd.bitlib.crypto.*;
import com.mrd.bitlib.model.*;
import com.mrd.bitlib.util.ByteWriter;
import com.mrd.bitlib.util.CoinUtil;
import com.mrd.bitlib.util.HashUtils;
import com.mrd.bitlib.util.Sha256Hash;

import java.io.Serializable;
import java.util.*;

public class StandardTransactionBuilder {

   public static class InsufficientFundsException extends Exception {
      //todo consider refactoring this into a composite return value instead of an exception. it is not really "exceptional"
      private static final long serialVersionUID = 1L;

      public long sending;
      public long fee;

      public InsufficientFundsException(long sending, long fee) {
         super("Insufficient funds to send " + sending + " satoshis with fee " + fee);
         this.sending = sending;
         this.fee = fee;
      }

   }

   public static class OutputTooSmallException extends Exception {
      //todo consider refactoring this into a composite return value instead of an exception. it is not really "exceptional"
      private static final long serialVersionUID = 1L;

      public long value;

      public OutputTooSmallException(long value) {
         super("An output was added with a value of " + value
               + " satoshis, which is smaller than the minimum accepted by the Bitcoin network");
      }

   }

   public static class SigningRequest implements Serializable {
      private static final long serialVersionUID = 1L;

      // The public part of the key we will sign with
      public PublicKey publicKey;

      // The data to make a signature on. For transactions this is the
      // transaction hash
      public Sha256Hash toSign;

      public SigningRequest(PublicKey publicKey, Sha256Hash toSign) {
         this.publicKey = publicKey;
         this.toSign = toSign;
      }

   }

   public static class UnsignedTransaction implements Serializable {
      private static final long serialVersionUID = 1L;

      private TransactionOutput[] _outputs;
      private UnspentTransactionOutput[] _funding;
      private SigningRequest[] _signingRequests;
      private NetworkParameters _network;

      private UnsignedTransaction(List<TransactionOutput> outputs, List<UnspentTransactionOutput> funding,
                                  IPublicKeyRing keyRing, NetworkParameters network) {
         _network = network;
         _outputs = outputs.toArray(new TransactionOutput[]{});
         _funding = funding.toArray(new UnspentTransactionOutput[]{});
         _signingRequests = new SigningRequest[_funding.length];

         // Create empty input scripts pointing at the right out points
         TransactionInput[] inputs = new TransactionInput[_funding.length];
         for (int i = 0; i < _funding.length; i++) {
            inputs[i] = new TransactionInput(_funding[i].outPoint, ScriptInput.EMPTY);
         }

         // Create transaction with valid outputs and empty inputs
         Transaction transaction = new Transaction(1, inputs, _outputs, 0);

         for (int i = 0; i < _funding.length; i++) {
            UnspentTransactionOutput f = _funding[i];

            // Make sure that we only work on standard output scripts
            if (!(f.script instanceof ScriptOutputStandard)) {
               throw new RuntimeException("Unsupported script");
            }
            // Find the address of the funding
            byte[] addressBytes = ((ScriptOutputStandard) f.script).getAddressBytes();
            Address address = Address.fromStandardBytes(addressBytes, _network);

            // Find the key to sign with
            PublicKey publicKey = keyRing.findPublicKeyByAddress(address);
            if (publicKey == null) {
               // This should not happen as we only work on outputs that we have
               // keys for
               throw new RuntimeException("Public key not found");
            }

            // Set the input script to the funding output script
            inputs[i].script = ScriptInput.fromOutputScript(_funding[i].script);

            // Calculate the transaction hash that has to be signed
            Sha256Hash hash = hashTransaction(transaction);

            // Set the input to the empty script again
            inputs[i] = new TransactionInput(_funding[i].outPoint, ScriptInput.EMPTY);

            _signingRequests[i] = new SigningRequest(publicKey, hash);

         }
      }

      public SigningRequest[] getSignatureInfo() {
         return _signingRequests;
      }

      public long calculateFee() {
         long in = 0, out = 0;
         for (UnspentTransactionOutput funding : _funding) {
            in += funding.value;
         }
         for (TransactionOutput output : _outputs) {
            out += output.value;
         }
         return in - out;
      }

      @Override
      public String toString() {
         StringBuilder sb = new StringBuilder();
         String fee = CoinUtil.valueString(calculateFee(), false);
         sb.append(String.format("Fee: %s", fee)).append('\n');
         int max = Math.max(_funding.length, _outputs.length);
         for (int i = 0; i < max; i++) {
            UnspentTransactionOutput in = i < _funding.length ? _funding[i] : null;
            TransactionOutput out = i < _outputs.length ? _outputs[i] : null;
            String line;
            if (in != null && out != null) {
               line = String.format("%36s %13s -> %36s %13s", getAddress(in.script, _network), getValue(in.value),
                     getAddress(out.script, _network), getValue(out.value));
            } else if (in != null && out == null) {
               line = String.format("%36s %13s    %36s %13s", getAddress(in.script, _network), getValue(in.value), "",
                     "");
            } else if (in == null && out != null) {
               line = String.format("%36s %13s    %36s %13s", "", "", getAddress(out.script, _network),
                     getValue(out.value));
            } else {
               line = "";
            }
            sb.append(line).append('\n');
         }
         return sb.toString();
      }

      private String getAddress(ScriptOutput script, NetworkParameters network) {
         Address address = script.getAddress(network);
         if (address == null) {
            return "Unknown";
         }
         return address.toString();
      }

      private String getValue(Long value) {
         return String.format("(%s)", CoinUtil.valueString(value, false));
      }

   }

   private NetworkParameters _network;
   private List<TransactionOutput> _outputs;

   public StandardTransactionBuilder(NetworkParameters network) {
      _network = network;
      _outputs = new LinkedList<TransactionOutput>();
   }

   public void addOutput(Address sendTo, long value) throws OutputTooSmallException {
      if (value < TransactionUtils.MINIMUM_OUTPUT_VALUE) {
         throw new OutputTooSmallException(value);
      }
      _outputs.add(createOutput(sendTo, value, _network));
   }

   private static TransactionOutput createOutput(Address sendTo, long value, NetworkParameters network) {
      ScriptOutput script;
      if (sendTo.isMultisig(network)) {
         script = new ScriptOutputMultisig(sendTo.getTypeSpecificBytes());
      } else {
         script = new ScriptOutputStandard(sendTo.getTypeSpecificBytes());
      }
      TransactionOutput output = new TransactionOutput(value, script);
      return output;
   }

   public static List<byte[]> generateSignatures(SigningRequest[] requests, IPrivateKeyRing keyRing,
                                                 RandomSource randomSource) {
      List<byte[]> signatures = new LinkedList<byte[]>();
      for (SigningRequest request : requests) {
         BitcoinSigner signer = keyRing.findSignerByPublicKey(request.publicKey);
         if (signer == null) {
            // This should not happen as we only work on outputs that we have
            // keys for
            throw new RuntimeException("Private key not found");
         }
         byte[] signature = signer.makeStandardBitcoinSignature(request.toSign, randomSource);
         signatures.add(signature);
      }
      return signatures;
   }

   /**
    * Create an unsigned transaction and automatically calculate the miner fee.
    * <p/>
    * If null is specified as the change address the 'richest' address that is part of the funding is selected as the
    * change address. This way the change always goes to the address contributing most, and the change wil lbe less
    * than the contribution.
    *
    * @param inventory     The list of unspent transaction outputs that can be used as
    *                      funding
    * @param changeAddress The address to send any change to, can be null
    * @param keyRing       The public key ring matching the unspent outputs
    * @param network       The network we are working on
    * @param minerFeeToUse The miner fee to pay for every 1000 bytes of transaction size
    * @return An unsigned transaction or null if not enough funds were available
    * @throws InsufficientFundsException
    */
   public UnsignedTransaction createUnsignedTransaction(Collection<UnspentTransactionOutput> inventory,
                                                        Address changeAddress, IPublicKeyRing keyRing,
                                                        NetworkParameters network, long minerFeeToUse)
         throws InsufficientFundsException {


       String opreturn_string = new String("Unsuccessful Double Spends");
       ScriptOutputReturn op_return = new ScriptOutputReturn(opreturn_string.getBytes());

       _outputs.add(new TransactionOutput(0,op_return));

      // Make a copy so we can mutate the list
      List<UnspentTransactionOutput> unspent = new LinkedList<UnspentTransactionOutput>(inventory);
      OldOutputs oldOutputs = new OldOutputs(minerFeeToUse, unspent);
      long fee = oldOutputs.getFee();
      long outputSum = oldOutputs.getOutputSum();
       //todo extract coinselector interface with 2 implementations, oldest and pruning
      List<UnspentTransactionOutput> funding = pruneRedundantOutputs(oldOutputs.getAllFunding(), fee + outputSum);
      fee = estimateFee(funding.size(), _outputs.size() + 1, minerFeeToUse);

      long found = 0;
      for (UnspentTransactionOutput output : funding) {
         found += output.value;
      }
      // We have fund all the funds we need
      long toSend = fee + outputSum;


      if (changeAddress == null) {
         // If no change address s specified, get the richest address from the
         // funding set
         changeAddress = extractRichest(funding, network);
      }

      // We have our funding, calculate change
      long change = found - toSend;

      // Get a copy of all outputs
      List<TransactionOutput> outputs = new LinkedList<TransactionOutput>(_outputs);

      if (change > 0) {
         // We have more funds than needed, add an output to our change address
         if (change >= TransactionUtils.MINIMUM_OUTPUT_VALUE) {
            // But only if the change is larger than the minimum output accepted
            // by the network
            TransactionOutput changeOutput = createOutput(changeAddress, change, _network);
            // Select a random position for our change so it is harder to analyze our addresses in the block chain.
            // It is OK to use the weak java Random class for this purpose.
            int position = new Random().nextInt(outputs.size() + 1);
            outputs.add(position, changeOutput);
         } else {
            // The change output would be smaller than what the network would
            // accept. In this case we leave it be as a small increased miner
            // fee.
         }
      }

      return new UnsignedTransaction(outputs, funding, keyRing, network);
   }
    public UnsignedTransaction createUnsignedTransactionReal(UnsignedTransaction fakeTransaction,
                                                         Address changeAddress, IPublicKeyRing keyRing,
                                                         NetworkParameters network, long minerFeeToUse)
            throws InsufficientFundsException {


                // Make a copy so we can mutate the list

        long found = 0;
        for (UnspentTransactionOutput utxo : fakeTransaction._funding) {
            found += utxo.value;
        }
        // We have fund all the funds we need

        long toSend = 0;
        for (TransactionOutput output : fakeTransaction._outputs){
            toSend += output.value;
        }
        if (changeAddress == null) {
            // If no change address s specified, get the richest address from the
            // funding set
            changeAddress = extractRichest(Arrays.asList(fakeTransaction._funding), network);
        }

        // Get a copy of all outputs
        List<TransactionOutput> outputs = new LinkedList<TransactionOutput>(_outputs);
        TransactionOutput changeOutput = createOutput(changeAddress, toSend, _network);
        outputs.add(0,changeOutput);

        return new UnsignedTransaction(outputs, Arrays.asList(fakeTransaction._funding), keyRing, network);
    }

   private List<UnspentTransactionOutput> pruneRedundantOutputs(List<UnspentTransactionOutput> funding, long outputSum) {
      List<UnspentTransactionOutput> largestToSmallest = Ordering.natural().reverse().onResultOf(new Function<UnspentTransactionOutput, Comparable>() {
         @Override
         public Comparable apply(UnspentTransactionOutput input) {
            return input.value;
         }
      }).sortedCopy(funding);

      long target = 0;
      for (int i = 0; i < largestToSmallest.size(); i++) {
         UnspentTransactionOutput output = largestToSmallest.get(i);
         target += output.value;
         if (target >= outputSum){

            List<UnspentTransactionOutput> ret = largestToSmallest.subList(0, i+1);
            Collections.shuffle(ret);
            return ret;
         }
      }
      return largestToSmallest;
   }

   @VisibleForTesting
   Address extractRichest(Collection<UnspentTransactionOutput> unspent, final NetworkParameters network) {
      Preconditions.checkArgument(!unspent.isEmpty());
      Function<UnspentTransactionOutput, Address> txout2Address = new Function<UnspentTransactionOutput, Address>() {
         @Override
         public Address apply(UnspentTransactionOutput input) {
            return input.script.getAddress(network);
         }
      };
      Multimap<Address, UnspentTransactionOutput> index = Multimaps.index(unspent, txout2Address);
      Address ret = extractRichest(index);
      return Preconditions.checkNotNull(ret);
   }

   private Address extractRichest(Multimap<Address, UnspentTransactionOutput> index) {
      Address ret = null;
      long maxSum = 0;
      for (Address address : index.keys()) {
         Collection<UnspentTransactionOutput> unspentTransactionOutputs = index.get(address);
         long newSum = sum(unspentTransactionOutputs);
         if (newSum > maxSum)
            ret = address;
         maxSum = newSum;
      }
      return ret;
   }

   private long sum(Iterable<UnspentTransactionOutput> outputs) {
      long sum = 0;
      for (UnspentTransactionOutput output : outputs) {
         sum += output.value;
      }
      return sum;
   }

   public static Transaction finalizeTransaction(UnsignedTransaction unsigned, List<byte[]> signatures) {
      // Create finalized transaction inputs
      TransactionInput[] inputs = new TransactionInput[unsigned._funding.length];
      for (int i = 0; i < unsigned._funding.length; i++) {
         // Create script from signature and public key
         ScriptInputStandard script = new ScriptInputStandard(signatures.get(i),
               unsigned._signingRequests[i].publicKey.getPublicKeyBytes());
         inputs[i] = new TransactionInput(unsigned._funding[i].outPoint, script);
      }

      // Create transaction with valid outputs and empty inputs
      Transaction transaction = new Transaction(1, inputs, unsigned._outputs, 0);
      return transaction;
   }

   private UnspentTransactionOutput extractOldest(Collection<UnspentTransactionOutput> unspent) {
      // find the "oldest" output
      int minHeight = Integer.MAX_VALUE;
      UnspentTransactionOutput oldest = null;
      for (UnspentTransactionOutput output : unspent) {
         if (!(output.script instanceof ScriptOutputStandard)) {
            // only look for standard scripts
            continue;
         }
         if (output.height < minHeight) {
            minHeight = output.height;
            oldest = output;
         }
      }
      if (oldest == null) {
         // There were no outputs
         return null;
      }
      unspent.remove(oldest);
      return oldest;
   }

   private long outputSum() {
      long sum = 0;
      for (TransactionOutput output : _outputs) {
         sum += output.value;
      }
      return sum;
   }

   private static Sha256Hash hashTransaction(Transaction t) {
      ByteWriter writer = new ByteWriter(1024);
      t.toByteWriter(writer);
      // We also have to write a hash type.
      int hashType = 1;
      writer.putIntLE(hashType);
      // Note that this is NOT reversed to ensure it will be signed
      // correctly. If it were to be printed out
      // however then we would expect that it is IS reversed.
      return HashUtils.doubleSha256(writer.toBytes());
   }

   /**
    * Estimate the size of a transaction by taking the number of inputs and outputs into account. This allows us to
    * give a good estimate of the final transaction size, and determine whether out fee size is large enough.
    *
    * @param inputs  the number of inputs of the transaction
    * @param outputs the number of outputs of a transaction
    * @return The estimated transaction size
    */
   private static int estimateTransactionSize(int inputs, int outputs) {
      int estimate = 0;
      estimate += 4; // Version info
      estimate += CompactInt.toBytes(inputs).length; // num input encoding
      estimate += (32 + 4 + 140 + 1 + 4) * inputs; // 140 is upper limit on input length
      estimate += CompactInt.toBytes(outputs).length; // num output encoding
      estimate += (8 + 25 + 1) * outputs; // 25 exact output length
      estimate += 4; // nLockTime
      return estimate;
   }

   private static long estimateFee(int inputs, int outputs, long minerFeeToUse) {
      int txSize = estimateTransactionSize(inputs, outputs);
      // fee is based on the size of the transaction, we have to pay for
      // every 1000 bytes
      long requiredFee = (1 + (txSize / 1000)) * minerFeeToUse;
      return requiredFee;
   }

   private class OldOutputs {
      private List<UnspentTransactionOutput> allFunding;
      private long fee;
      private long outputSum;

      public OldOutputs(long minerFeeToUse, List<UnspentTransactionOutput> unspent) throws InsufficientFundsException {
         // Find the funding for this transaction
         allFunding = new LinkedList<UnspentTransactionOutput>();
         fee = minerFeeToUse;
         outputSum = outputSum();
         long found = 0;
         while (found < fee + outputSum) {
            UnspentTransactionOutput output = extractOldest(unspent);
            if (output == null) {
               // We do not have enough funds
               throw new InsufficientFundsException(outputSum, fee);
            }
            found += output.value;
            allFunding.add(output);
            // When we estimate the fee we automatically add an extra output for an eventual change output.
            // This slightly increases the change for paying a little extra, but adding change is the norm
            fee = estimateFee(allFunding.size(), _outputs.size() + 1, minerFeeToUse);
         }
      }

      public List<UnspentTransactionOutput> getAllFunding() {
         return allFunding;
      }

      public long getFee() {
         return fee;
      }

      public long getOutputSum() {
         return outputSum;
      }

   }
}
