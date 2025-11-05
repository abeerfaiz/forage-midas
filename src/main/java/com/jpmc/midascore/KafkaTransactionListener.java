package com.jpmc.midascore;

import com.jpmc.midascore.entity.TransactionRecord;
import com.jpmc.midascore.entity.UserRecord;
import com.jpmc.midascore.foundation.Transaction;
import com.jpmc.midascore.repository.TransactionRepository;
import com.jpmc.midascore.repository.UserRepository;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class KafkaTransactionListener {

    private final Logger logger = LoggerFactory.getLogger(KafkaTransactionListener.class);

    private final UserRepository userRepository;
    private final TransactionRepository transactionRepository;

    public KafkaTransactionListener(UserRepository userRepository, TransactionRepository transactionRepository) {
        this.userRepository = userRepository;
        this.transactionRepository = transactionRepository;
    }

    @Transactional
    @KafkaListener(topics = "${general.kafka-topic}", groupId = "midas-core")
    public void listen(Transaction transaction) {

        if(transaction == null){
            logger.error("Transaction is null");
            return;
        }

        long senderId = transaction.getSenderId();
        long recipientId = transaction.getRecipientId();
        float amount = transaction.getAmount();

        UserRecord sender = userRepository.findById(senderId);
        UserRecord recipient = userRepository.findById(recipientId);

        if(sender == null || recipient == null){
            logger.debug("Discarded tx due to invalid user id. sender {} recipient {}", senderId, recipientId);
            return;
        }

        if(sender.getBalance() < amount){
            logger.debug("Discarded tx due to insufficient funds. sender {} balance {} amount {}", senderId, sender.getBalance(), amount);
            return;
        }

        sender.setBalance(sender.getBalance() - amount);
        recipient.setBalance(recipient.getBalance() + amount);

        userRepository.save(sender);
        userRepository.save(recipient);

        TransactionRecord transactionRecord = new TransactionRecord(sender, recipient, amount);
        transactionRepository.save(transactionRecord);

        logger.debug("Processed tx sender {} recipient {} amount {}", senderId, recipientId, amount);

        var waldorf = userRepository.findByName("waldorf");
        System.out.println("Waldorf balance: " + waldorf.getBalance());

    }
}
