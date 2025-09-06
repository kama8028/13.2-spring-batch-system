package com.system.batch;

import java.time.LocalDateTime;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.database.JdbcBatchItemWriter;
import org.springframework.batch.item.database.JdbcPagingItemReader;
import org.springframework.batch.item.database.Order;
import org.springframework.batch.item.database.builder.JdbcBatchItemWriterBuilder;
import org.springframework.batch.item.database.builder.JdbcPagingItemReaderBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;

@Configuration
@RequiredArgsConstructor
public class OrderRecoveryJobConfig {
  private final JobRepository jobRepository;
  private final PlatformTransactionManager transactionManager;
  private DataSource dataSource;

  @Bean
  public Job orderRecoveryJob(){
    return new JobBuilder("orderRecoveryJob", jobRepository)
      .start(orderRecoveryStep())
      .build();
  }

  @Bean
  public Step orderRecoveryStep() {
    return new StepBuilder("orderRecoveryStep", jobRepository)
      .<HackedOrder, HackedOrder>chunk(10, transactionManager)
      .reader(compromisedOrderReader())
      .processor(orderStatusProcessor())
      .writer(orderStatusWriter())
      .build();
  }

  @Bean
  public JdbcPagingItemReader<HackedOrder> compromisedOrderReader() {
    return new JdbcPagingItemReaderBuilder<HackedOrder>()
      .name("compromisedOrderReader")
      .dataSource(dataSource)
      .pageSize(10)
      .selectClause("SELECT id, cutomer_id, order_datetime, status, shipping_id")
      .fromClause("FROM orders")
      .whereClause("WHERE (status = 'SHIPPED' and shipping_id is null)" + "or (status = 'CANCELLED' and shipping_id is not null)")
      .sortKeys(Map.of("id", Order.ASCENDING))
      .beanRowMapper(HackedOrder.class)
      .build();
  }

  @Bean
  public ItemProcessor<HackedOrder, HackedOrder> orderStatusProcessor(){
    return order1 -> {
      if (order1.getShippingId() == null) {
        order1.setStatus("READY_FOR_SHIPMENT");        
      }
      else {
        order1.setStatus("SHIPPED");
      }
      return order1;    
  };
  }

  @Bean
  public JdbcBatchItemWriter<HackedOrder> orderStatusWriter(){
    return new JdbcBatchItemWriterBuilder<HackedOrder>()
      .dataSource(dataSource)
      .sql("UPDATE orders SET status = :status WHERE id = :id")
      .beanMapped()
      .assertUpdates(true) //업데이트가 하나라도 실패하면 다 롤백한다.해당 프로세스는 실패한다. 
      .build();
  }

  @Data
  @NoArgsConstructor
  public static class HackedOrder {
    private Long id;
    private Long customerId;
    private LocalDateTime orderDateTime;
    private String status;
    private String shippingId;
  }
}

