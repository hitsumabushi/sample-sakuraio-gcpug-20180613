package main

import (
	"bufio"
	"context"
	"fmt"
	"log"
	"os"

	"cloud.google.com/go/pubsub"
)

const (
	projectID = ""
	topicName = ""
	dataFile  = "data.list"
)

func main() {
	ctx := context.Background()
	c, err := pubsub.NewClient(ctx, projectID)

	if err != nil {
		log.Fatal("new client:", err)
	}
	topic := c.Topic(topicName)
	defer topic.Stop()

	var results []*pubsub.PublishResult

	file, _ := os.Open(dataFile)
	defer file.Close()
	scanner := bufio.NewScanner(file)
	var line string
	for scanner.Scan() {
		line = scanner.Text()
		r := topic.Publish(ctx, &pubsub.Message{
			Data: []byte(line),
		})
		results = append(results, r)
	}

	for _, r := range results {
		_, err := r.Get(ctx)
		if err != nil {
			fmt.Println(err)
		}
	}
}
