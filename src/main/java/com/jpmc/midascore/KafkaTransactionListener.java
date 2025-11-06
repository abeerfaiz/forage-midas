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
import org.springframework.web.client.RestTemplate;

@Component
public class KafkaTransactionListener {

    private final Logger logger = LoggerFactory.getLogger(KafkaTransactionListener.class);

    private final UserRepository userRepository;
    private final TransactionRepository transactionRepository;
    private final RestTemplate restTemplate;

    public KafkaTransactionListener(UserRepository userRepository, TransactionRepository transactionRepository, RestTemplate restTemplate) {
        this.userRepository = userRepository;
        this.transactionRepository = transactionRepository;
        this.restTemplate = restTemplate;
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

        float incentive = 0f;
        try {
            Incentive response = restTemplate.postForObject(
                    "http://localhost:8080/incentive", transaction, Incentive.class);
            if (response != null) {
                incentive = Math.max(0f, response.getAmount());
            }
            logger.info("Incentive API returned amount: {}", incentive);
        } catch (Exception e) {
            logger.warn("Incentive API call failed, defaulting to zero", e);
        }

        sender.setBalance(sender.getBalance() - amount);
        recipient.setBalance(recipient.getBalance() + amount + incentive);

        userRepository.save(sender);
        userRepository.save(recipient);

        TransactionRecord transactionRecord = new TransactionRecord(sender, recipient, amount, incentive);
        transactionRepository.save(transactionRecord);

        logger.debug("Processed tx sender {} recipient {} amount {} incentive {}", senderId, recipientId, amount, incentive);

        var wilbur = userRepository.findByName("wilbur");
        System.out.println("wilbur balance: " + wilbur.getBalance());

    }
}
