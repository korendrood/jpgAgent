/*
 * Copyright (c) 2016, Adam Brusselback
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.gosimple.jpgagent.job.step;


import com.gosimple.jpgagent.*;
import com.gosimple.jpgagent.annotation.AnnotationUtil;
import com.gosimple.jpgagent.database.Database;
import com.gosimple.jpgagent.database.DatabaseAuth;
import com.gosimple.jpgagent.email.EmailUtil;
import com.gosimple.jpgagent.job.Job;
import com.gosimple.jpgagent.thread.CancellableRunnable;

import java.io.*;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class JobStep implements CancellableRunnable
{
    private final Job job;
    private int job_step_log_id;
    private StepStatus step_status;
    private int step_result;
    private String step_output;
    private final int step_id;
    private final String step_name;
    private final String step_description;
    private final StepType step_type;
    private String code;
    private final OnError on_error;
    private OSType os_type;
    private final String connection_string;

    private Statement running_statement;
    private Process running_process;
    private Long start_time;

    /*
    * Annotation set parameters.
     */
    // If true, will run in parallel with previous step.
    private Boolean run_in_parallel = false;
    // Timeout setting to abort job if running longer than this value.
    private Long job_step_timeout = null;
    // Database name
    private String database_name = null;
    // Database host
    private String database_host = null;
    // Database login to use
    private String database_login = null;
    // Database password to use
    private String database_password = null;
    // Database auth query
    private String database_auth_query = null;
    // List of status to send an email on
    private final List<StepStatus> email_on = new ArrayList<>();
    // Email to list
    private String[] email_to = null;
    // Email subject
    private String email_subject = null;
    // Email body
    private String email_body = null;

    public JobStep(final Job job, final int step_id, final String step_name, final String step_description, final StepType step_type, final String code, final String connection_string, final String database_name, final OnError on_error)
    {
        Config.INSTANCE.logger.debug("JobStep instantiation begin.");
        this.job = job;
        this.step_id = step_id;
        this.step_name = step_name;
        this.step_description = step_description;
        this.step_type = step_type;
        this.code = code;
        this.connection_string = connection_string;
        this.database_name = database_name;
        this.on_error = on_error;
        String os_name = System.getProperty("os.name");
        if (os_name.startsWith("Windows"))
        {
            os_type = OSType.WIN;
        }
        else if (os_name.startsWith("LINUX") || os_name.startsWith("Linux") || os_name.startsWith("Mac"))
        {
            os_type = OSType.NIX;
        }

        processAnnotations();
    }

    public void run()
    {
        this.start_time = System.currentTimeMillis();
        // Insert the job step log and get the id
        this.job_step_log_id = JobStepLog.startLog(job.getJobLogId(), step_id);

        switch (step_type)
        {
            case SQL:
            {
                Config.INSTANCE.logger.debug("Executing SQL step: {}", step_id);
                try
                {
                    // Throw error if attempting to run a job step with "remote" instead of "local"
                    if(connection_string != null && !connection_string.isEmpty())
                    {
                        throw new IllegalArgumentException("Remote connection types are not supported by jpgAgent. Please configure your job step to use annotations for remote connections.");
                    }

                    List<DatabaseAuth> db_auth = new ArrayList<>();

                    // If there is an db_auth query, run it and add all results to the db_auth list
                    if (database_auth_query != null)
                    {
                        try (Connection connection = Database.INSTANCE.getConnection(getHost(), getDatabase()))
                        {
                            try (Statement statement = connection.createStatement())
                            {
                                this.running_statement = statement;
                                try(ResultSet result = statement.executeQuery(database_auth_query))
                                {
                                    while(result.next())
                                    {
                                        db_auth.add(new DatabaseAuth(result.getString(1), result.getString(2)));
                                    }
                                }
                                this.running_statement = null;
                            }
                        }
                    }
                    // If there were explicit credentials passed in, add them to the db_auth list.
                    if(database_login != null || database_password != null)
                    {
                        db_auth.add(new DatabaseAuth(database_login, database_password));
                    }
                    // If nothing else was added to the auth list so far, add the configured jpgAgent credentials.
                    if(db_auth.size() == 0)
                    {
                        db_auth.add(new DatabaseAuth(Config.INSTANCE.db_user, Config.INSTANCE.db_password));
                    }

                    for(DatabaseAuth auth : db_auth)
                    {
                        try (Connection connection = Database.INSTANCE.getConnection(getHost(), getDatabase(), auth.getUser(), auth.getPass());
                             Statement statement = connection.createStatement())
                        {
                            this.running_statement = statement;
                            Config.INSTANCE.logger.debug("SQL step: {} starting.", step_id);
                            statement.execute(code);
                            this.running_statement = null;
                            step_result = 1;
                            step_status = StepStatus.SUCCEED;
                            Config.INSTANCE.logger.debug("SQL step: {} completed successfully.", step_id);
                        }
                    }
                }
                catch (final Exception e)
                {
                    Config.INSTANCE.logger.debug("SQL step: {} completed unsuccessfully.", step_id);
                    step_output = e.getMessage();
                    if (Thread.currentThread().isInterrupted())
                    {
                        step_result = 0;
                        step_status = StepStatus.ABORTED;
                    }
                    else if (on_error.equals(OnError.FAIL))
                    {
                        step_result = -1;
                        step_status = StepStatus.FAIL;
                    }
                    else if (on_error.equals(OnError.IGNORE))
                    {
                        step_result = -1;
                        step_status = StepStatus.IGNORE;
                    }
                    else if (on_error.equals(OnError.SUCCEED))
                    {
                        step_result = -1;
                        step_status = StepStatus.SUCCEED;
                    }
                }
                Config.INSTANCE.logger.debug("SQL step: {} mostly done.", step_id);
                break;
            }
            case BATCH:
            {
                Config.INSTANCE.logger.debug("Executing Batch step: {}", step_id);

                try
                {
                    final String fileExtension;
                    if (os_type.equals(OSType.WIN))
                    {
                        fileExtension = ".bat";
                    }
                    else
                    {
                        fileExtension = ".sh";
                    }

                    final File tmp_file_script = File.createTempFile("pga_", fileExtension, null);
                    tmp_file_script.deleteOnExit();
                    tmp_file_script.setWritable(true);
                    tmp_file_script.setExecutable(true);

                    try
                    {
                        // Replace line breaks for each OS type.
                        code = code.replaceAll("\\r\\n|\\r|\\n", System.getProperty("line.separator"));


                        try(final BufferedWriter buffered_writer = new BufferedWriter(new FileWriter(tmp_file_script)))
                        {
                            buffered_writer.write(this.code);
                        }

                        final ProcessBuilder process_builder = new ProcessBuilder(tmp_file_script.getAbsolutePath());
                        this.running_process = process_builder.start();
                        this.running_process.waitFor();


                        final StringBuilder string_builder = new StringBuilder();
                        try (final BufferedReader buffered_reader_out = new BufferedReader(new InputStreamReader(this.running_process.getInputStream()));
                             final BufferedReader buffered_reader_error = new BufferedReader(new InputStreamReader(this.running_process.getErrorStream())))
                        {
                            String line;
                            // Get normal output.
                            while ((line = buffered_reader_out.readLine()) != null)
                            {
                                string_builder.append(line);
                                string_builder.append(System.getProperty("line.separator"));
                            }
                            // Get error output.
                            while ((line = buffered_reader_error.readLine()) != null)
                            {
                                string_builder.append(line);
                                string_builder.append(System.getProperty("line.separator"));
                            }
                        }

                        this.step_output = string_builder.toString();
                        this.step_result = running_process.exitValue();
                        switch (step_result)
                        {
                            case 0:
                            {
                                step_status = StepStatus.SUCCEED;
                                Config.INSTANCE.logger.debug("Batch step: {} completed successfully.", step_id);
                                break;
                            }
                            case 1:
                            default:
                            {
                                step_status = StepStatus.FAIL;
                                Config.INSTANCE.logger.debug("Batch step: {} completed unsuccessfully.", step_id);
                                break;
                            }
                        }
                    }
                    catch (InterruptedException e)
                    {
                        this.step_result = running_process.exitValue();
                        Config.INSTANCE.logger.debug("Batch step: {} completed unsuccessfully.", step_id);
                        this.step_status = StepStatus.ABORTED;
                    }
                    catch (Exception e)
                    {
                        this.step_result = running_process.exitValue();
                        Config.INSTANCE.logger.debug("Batch step: {} completed unsuccessfully.", step_id);
                        if (this.on_error.equals(OnError.FAIL))
                        {
                            this.step_status = StepStatus.FAIL;
                        }
                        else if (this.on_error.equals(OnError.IGNORE))
                        {
                            this.step_status = StepStatus.IGNORE;
                        }
                        else if (this.on_error.equals(OnError.SUCCEED))
                        {
                            this.step_status = StepStatus.SUCCEED;
                        }
                    }
                    finally
                    {
                        this.running_process = null;
                        tmp_file_script.delete();
                    }
                }
                catch (IOException e)
                {
                    this.step_result = running_process.exitValue();
                    Config.INSTANCE.logger.debug("Batch step: {} completed unsuccessfully.", step_id);
                    if (this.on_error.equals(OnError.FAIL))
                    {
                        this.step_status = StepStatus.FAIL;
                    }
                    else if (this.on_error.equals(OnError.IGNORE))
                    {
                        this.step_status = StepStatus.IGNORE;
                    }
                    else if (this.on_error.equals(OnError.SUCCEED))
                    {
                        this.step_status = StepStatus.SUCCEED;
                    }
                }
                break;
            }
        }


        Config.INSTANCE.logger.debug("Made it here: {}", step_id);
        // Update the job step log record with the result of the job step.
        JobStepLog.finishLog(job_step_log_id, step_status, step_result, step_output);

        if(email_on.contains(step_status))
        {
            // Token replacement
            email_subject = email_subject.replaceAll(Config.INSTANCE.status_token, step_status.name());
            email_body = email_body.replaceAll(Config.INSTANCE.status_token, step_status.name());

            email_subject = email_subject.replaceAll(Config.INSTANCE.job_name_token, job.getJobName());
            email_body = email_body.replaceAll(Config.INSTANCE.job_name_token, job.getJobName());

            email_subject = email_subject.replaceAll(Config.INSTANCE.job_step_name_token, step_name);
            email_body = email_body.replaceAll(Config.INSTANCE.job_step_name_token, step_name);

            // Send email
            EmailUtil.sendEmailFromNoReply(email_to, email_subject, email_body);
        }
    }

    /**
     * Assign any values from annotations.
     */
    private void processAnnotations()
    {
        try
        {
            Map<String, String> annotations = AnnotationUtil.parseAnnotations(step_description);
            if(annotations.containsKey(JobStepAnnotations.RUN_IN_PARALLEL.name()))
            {
                run_in_parallel = AnnotationUtil.parseValue(JobStepAnnotations.RUN_IN_PARALLEL, annotations.get(JobStepAnnotations.RUN_IN_PARALLEL.name()), Boolean.class);
            }
            if(annotations.containsKey(JobStepAnnotations.JOB_STEP_TIMEOUT.name()))
            {
                job_step_timeout = AnnotationUtil.parseValue(JobStepAnnotations.JOB_STEP_TIMEOUT, annotations.get(JobStepAnnotations.JOB_STEP_TIMEOUT.name()), Long.class);
            }
            if(annotations.containsKey(JobStepAnnotations.DATABASE_NAME.name()))
            {
                database_name = AnnotationUtil.parseValue(JobStepAnnotations.DATABASE_NAME, annotations.get(JobStepAnnotations.DATABASE_NAME.name()), String.class);
            }
            if(annotations.containsKey(JobStepAnnotations.DATABASE_HOST.name()))
            {
                database_host = AnnotationUtil.parseValue(JobStepAnnotations.DATABASE_HOST, annotations.get(JobStepAnnotations.DATABASE_HOST.name()), String.class);
            }
            if(annotations.containsKey(JobStepAnnotations.DATABASE_LOGIN.name()))
            {
                database_login = AnnotationUtil.parseValue(JobStepAnnotations.DATABASE_LOGIN, annotations.get(JobStepAnnotations.DATABASE_LOGIN.name()), String.class);
            }
            if(annotations.containsKey(JobStepAnnotations.DATABASE_PASSWORD.name()))
            {
                database_password = AnnotationUtil.parseValue(JobStepAnnotations.DATABASE_PASSWORD, annotations.get(JobStepAnnotations.DATABASE_PASSWORD.name()), String.class);
            }
            if(annotations.containsKey(JobStepAnnotations.DATABASE_AUTH_QUERY.name()))
            {
                database_auth_query = AnnotationUtil.parseValue(JobStepAnnotations.DATABASE_AUTH_QUERY, annotations.get(JobStepAnnotations.DATABASE_AUTH_QUERY.name()), String.class);
            }
            if(annotations.containsKey(JobStepAnnotations.EMAIL_ON.name()))
            {
                for(String email_on_string : AnnotationUtil.parseValue(JobStepAnnotations.EMAIL_ON, annotations.get(JobStepAnnotations.EMAIL_ON.name()), String.class).split(";"))
                {
                    email_on.add(StepStatus.valueOf(email_on_string));
                }
            }
            if(annotations.containsKey(JobStepAnnotations.EMAIL_TO.name()))
            {
                email_to = AnnotationUtil.parseValue(JobStepAnnotations.EMAIL_TO, annotations.get(JobStepAnnotations.EMAIL_TO.name()), String.class).split(";");
            }
            if(annotations.containsKey(JobStepAnnotations.EMAIL_SUBJECT.name()))
            {
                email_subject = AnnotationUtil.parseValue(JobStepAnnotations.EMAIL_SUBJECT, annotations.get(JobStepAnnotations.EMAIL_SUBJECT.name()), String.class);
            }
            if(annotations.containsKey(JobStepAnnotations.EMAIL_BODY.name()))
            {
                email_body = AnnotationUtil.parseValue(JobStepAnnotations.EMAIL_BODY, annotations.get(JobStepAnnotations.EMAIL_BODY.name()), String.class);
            }
        }
        catch (Exception e)
        {
            Config.INSTANCE.logger.error("An issue with the annotations on job_id/job_step_id: " + job.getJobId() + "/" + step_id + " has stopped them from being processed.");
        }
        Config.INSTANCE.logger.debug("JobStep instantiation complete.");
    }

    private String getHost()
    {
        if(database_host != null)
        {
            return database_host;
        }
        else
        {
            return Config.INSTANCE.db_host;
        }
    }

    private String getDatabase()
    {
        return database_name;
    }

    /**
     * Returns if the job is timed out or not.
     * @return
     */
    public boolean isTimedOut()
    {
        if(null != job_step_timeout && null != start_time)
        {
            return System.currentTimeMillis() - start_time > job_step_timeout;
        }
        else
        {
            return false;
        }
    }

    /**
     * Should stop any long running process the thread was doing to exit gracefully as quickly as possible.
     */
    @Override
    public void cancelTask()
    {
        Config.INSTANCE.logger.debug("Job step: {} cancelled.", this.step_id);
        switch (step_type)
        {
            case SQL:
                if (running_statement != null)
                {
                    try
                    {
                        running_statement.cancel();
                    }
                    catch (SQLException e)
                    {
                        Config.INSTANCE.logger.error("There was an error canceling the job step.");
                        Config.INSTANCE.logger.error("Exception: " + e.toString());
                        Config.INSTANCE.logger.error("Message: " + e.getMessage());
                    }
                }
                break;
            case BATCH:
                if (running_process != null && running_process.isAlive())
                {
                    running_process.destroy();
                }
                break;
        }

    }

    /**
     * Gets the StepStatus of the JobStep.
     *
     * @return
     */
    public StepStatus getStepStatus()
    {
        return step_status;
    }

    /**
     * Gets the OnError of the JobStep.
     *
     * @return
     */
    public OnError getOnError()
    {
        return on_error;
    }

    /**
     * Returns if the job can run in parallel with the previous step.
     * @return
     */
    public Boolean canRunInParallel()
    {
        return this.run_in_parallel;
    }
}
