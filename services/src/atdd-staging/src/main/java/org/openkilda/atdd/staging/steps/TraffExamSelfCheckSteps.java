package org.openkilda.atdd.staging.steps;

import org.openkilda.atdd.staging.model.traffexam.Bandwidth;
import org.openkilda.atdd.staging.model.traffexam.Exam;
import org.openkilda.atdd.staging.model.traffexam.ExamReport;
import org.openkilda.atdd.staging.model.traffexam.Host;
import org.openkilda.atdd.staging.model.traffexam.TimeLimit;
import org.openkilda.atdd.staging.model.traffexam.Vlan;
import org.openkilda.atdd.staging.service.ExamNotFinishedException;
import org.openkilda.atdd.staging.service.NoResultsFoundException;
import org.openkilda.atdd.staging.service.OperationalException;
import org.openkilda.atdd.staging.service.TraffExamService;

import cucumber.api.java.en.Then;
import cucumber.api.java8.En;
import org.junit.Assert;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.concurrent.TimeUnit;
import javax.naming.directory.InvalidAttributesException;

public class TraffExamSelfCheckSteps implements En {
    @Autowired
    private TraffExamService traffExam;

    @Then("setup and check traffic exam")
    public void simpleTest()
            throws OperationalException, NoResultsFoundException, InterruptedException, InvalidAttributesException, ExamNotFinishedException {
        Host sourceHost = traffExam.hostByName("tg1");
        Host destHost = traffExam.hostByName("tg2");

        Exam exam = new Exam(sourceHost, destHost)
                .withVlan(new Vlan(20))
//                .withBandwidthLimit(new Bandwidth(128))
                .withBandwidthLimit(new Bandwidth(512))
                .withTimeLimitSeconds(new TimeLimit(5));
        exam = traffExam.startExam(exam);
        TimeUnit.SECONDS.sleep(6);

        ExamReport report = traffExam.fetchReport(exam);

        Assert.assertFalse(report.isError());
        Assert.assertTrue(report.isTraffic());
    }
}
