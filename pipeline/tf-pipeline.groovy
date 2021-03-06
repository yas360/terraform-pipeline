node {

    stage name: 'Clean up'
        if (fileExists("\$WORKSPACE/iam/.terraform/terraform.tfstate")) {
            sh "rm -rf \$WORKSPACE/iam/.terraform/terraform.tfstate"
        }
        if (fileExists("\$WORKSPACE/iam/tf-plan-status")) {
            sh "rm \$WORKSPACE/iam/tf-plan-status"
        }
        if (fileExists("\$WORKSPACE/iam/tf-validate-status")) {
            sh "rm \$WORKSPACE/iam/tf-validate-status"
        }

    stage name: 'Checkout'
        echo "############# Checkout: Checking terraform files from source repository #############"
        git url: 'https://github.com/yas360/terraform-pipeline.git'
        def tfHome = tool name: 'terraform', type: 'com.cloudbees.jenkins.plugins.customtools.CustomTool'
        env.PATH = "${tfHome}:${env.PATH}"
        sh 'terraform --version'

    stage name: 'Init'
        echo "############# Initialize a new or existing terraform environment #############"
        sh 'cd \$WORKSPACE/iam/; terraform init'

    stage name: 'Validate'
        echo "############# Validate: Checking syntax of terraform files #############"
        sh 'set +e;cd \$WORKSPACE/iam/; terraform validate;echo \$? > tf-validate-status'
        def valExitCode = readFile('iam/tf-validate-status').trim()
        if (valExitCode == "1") {
            currentBuild.result = 'FAILURE'
        }

    stage name: 'Plan'
        echo "############# Plan: Creating terraform execution plan #############"
        sh 'set +e; cd \$WORKSPACE/iam/; terraform plan -out=\$BUILD_NUMBER-plan.out -detailed-exitcode; echo \$? > tf-plan-status'
        def planExitCode = readFile('iam/tf-plan-status').trim()
        echo "Terraform Plan Exit Code: ${planExitCode}"
        def apply = false
        if (planExitCode == "0") {
            currentBuild.result = 'SUCCESS'
        }
        if (planExitCode == "1") {
            slackSend channel: '#ci', color: 'danger', message: "Plan Failed: ${env.JOB_NAME} - ${env.BUILD_NUMBER}"
            currentBuild.result = 'FAILURE'
        }
        if (planExitCode == "2") {
            stash name: "plan", includes: "iam/${env.BUILD_NUMBER}-plan.out"
            slackSend channel: '#ci', color: 'good', message: "Plan Awaiting Approval: ${env.JOB_NAME} - ${env.BUILD_NUMBER}"
            try {
                input message: 'Apply Plan?', ok: 'Apply'
                apply = true
            } catch (err) {
                apply = false
                currentBuild.result = 'UNSTABLE'
            }
        }

    if (apply) {
        stage name: 'Apply'
            echo "############# Apply: Applying the pre-determined set of actions generated by the terraform plan execution plan #############"
            unstash 'plan'
            if (fileExists("\$WORKSPACE/iam/status.apply")) {
                sh 'rm \$WORKSPACE/iam/status.apply'
            }
            sh 'set +e; cd \$WORKSPACE/iam/; terraform apply \$BUILD_NUMBER-plan.out; echo \$? > status.apply'
            def applyExitCode = readFile('iam/status.apply').trim()
            if (applyExitCode == "0") {
                echo "Successfully applied changes"
                currentBuild.result = 'SUCCESS'
            } else {
                currentBuild.result = 'FAILURE'
            }
        }
}

